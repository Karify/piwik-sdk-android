/*
 * Android SDK for Piwik
 *
 * @link https://github.com/piwik/piwik-android-sdk
 * @license https://github.com/piwik/piwik-sdk-android/blob/master/LICENSE BSD-3 Clause
 */

package org.piwik.sdk.dispatcher;

import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import org.json.JSONObject;
import org.piwik.sdk.Piwik;
import org.piwik.sdk.TrackMe;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

/**
 * Sends json POST request to tracking url http://piwik.example.com/piwik.php with body
 * <p/>
 * {
 * "requests": [
 * "?idsite=1&url=http://example.org&action_name=Test bulk log Pageview&rec=1",
 * "?idsite=1&url=http://example.net/test.htm&action_name=Another bul k page view&rec=1"
 * ],
 * "token_auth": "33dc3f2536d3025974cccb4b4d2d98f4"
 * }
 */
@SuppressWarnings("deprecation")
public class Dispatcher {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String LOGGER_TAG = Piwik.LOGGER_PREFIX + "Dispatcher";
    private final BlockingQueue<TrackMe> mDispatchQueue = new LinkedBlockingQueue<>();
    private final Object mThreadControl = new Object();
    private final Semaphore mSleepToken = new Semaphore(0);
    private final Piwik mPiwik;
    private final HttpUrl mHttpUrl;
    private final String mAuthToken;

    private List<Packet> mDryRunOutput = Collections.synchronizedList(new ArrayList<Packet>());
    public static final int DEFAULT_CONNECTION_TIMEOUT = 5 * 1000;  // 5s
    private volatile int mTimeOut = DEFAULT_CONNECTION_TIMEOUT;
    private volatile boolean mRunning = false;

    public static final long DEFAULT_DISPATCH_INTERVAL = 120 * 1000; // 120s
    private volatile long mDispatchInterval = DEFAULT_DISPATCH_INTERVAL;

    public Dispatcher(Piwik piwik, HttpUrl httpUrl, String authToken) {
        mPiwik = piwik;
        mHttpUrl = httpUrl;
        mAuthToken = authToken;
    }

    /**
     * Connection timeout in milliseconds
     *
     * @return timeout in milliseconds
     */
    public int getConnectionTimeOut() {
        return mTimeOut;
    }

    /**
     * Timeout when trying to establish connection and when trying to read a response.
     * Values take effect on next dispatch.
     *
     * @param timeOut timeout in milliseconds
     */
    public void setConnectionTimeOut(int timeOut) {
        mTimeOut = timeOut;
    }

    /**
     * Packets are collected and dispatched in batches, this intervals sets the pause between batches.
     *
     * @param dispatchInterval in milliseconds
     */
    public void setDispatchInterval(long dispatchInterval) {
        mDispatchInterval = dispatchInterval;
        if (mDispatchInterval != -1)
            launch();
    }

    public long getDispatchInterval() {
        return mDispatchInterval;
    }

    private boolean launch() {
        synchronized (mThreadControl) {
            if (!mRunning) {
                mRunning = true;
                new Thread(mLoop).start();
                return true;
            }
        }
        return false;
    }

    /**
     * Starts the dispatcher for one cycle if it is currently not working.
     * If the dispatcher is working it will skip the dispatch interval once.
     */
    public boolean forceDispatch() {
        if (!launch()) {
            mSleepToken.release();
            return false;
        }
        return true;
    }

    public void submit(TrackMe trackMe) {
        mDispatchQueue.add(trackMe);
        if (mDispatchInterval != -1)
            launch();
    }

    private Runnable mLoop = new Runnable() {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (mRunning) {
                try {
                    // Either we wait the interval or forceDispatch() granted us one free pass
                    mSleepToken.tryAcquire(mDispatchInterval, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                int count = 0;
                List<TrackMe> availableEvents = new ArrayList<>();
                mDispatchQueue.drainTo(availableEvents);
                Timber.tag(LOGGER_TAG).d("Drained %s events.", availableEvents.size());
                TrackerBulkURLWrapper wrapper = new TrackerBulkURLWrapper(mHttpUrl, availableEvents, mAuthToken);
                Iterator<TrackerBulkURLWrapper.Page> pageIterator = wrapper.iterator();
                while (pageIterator.hasNext()) {
                    TrackerBulkURLWrapper.Page page = pageIterator.next();

                    // use doGET when only event on current page
                    if (page.elementsCount() > 1) {
                        JSONObject eventData = wrapper.getEvents(page);
                        if (eventData == null)
                            continue;
                        if (dispatch(new Packet(wrapper.getApiUrl(), eventData)))
                            count += page.elementsCount();
                    } else {
                        HttpUrl targetUri = wrapper.getEventUrl(page);
                        if (targetUri == null)
                            continue;
                        if (dispatch(new Packet(targetUri)))
                            count += 1;
                    }
                }
                Timber.tag(LOGGER_TAG).d("Dispatched %s events.", count);
                synchronized (mThreadControl) {
                    // We may be done or this was a forced dispatch
                    if (mDispatchQueue.isEmpty() || mDispatchInterval < 0) {
                        mRunning = false;
                        break;
                    }
                }
            }
        }
    };

    @VisibleForTesting
    public boolean dispatch(@NonNull Packet packet) {
        // Some error checking
        if (packet.getJSONObject() != null && packet.getJSONObject().length() == 0) {
            return false;
        }
        if (mPiwik.isDryRun()) {
            mDryRunOutput.add(packet);
            Timber.tag(LOGGER_TAG).d("DryRun, stored HttpRequest, now %s.", mDryRunOutput.size());
            return true;
        }

        if (!mDryRunOutput.isEmpty())
            mDryRunOutput.clear();

        try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            OkHttpClient client = builder
                    .connectTimeout(mTimeOut, TimeUnit.SECONDS)
                    .readTimeout(mTimeOut, TimeUnit.SECONDS)
                    .build();
            final Request request;
            final RequestBody body;
            // IF there is json data we want to do a post
            if (packet.getJSONObject() != null) {
                body = RequestBody.create(JSON, packet.getJSONObject().toString());
                request = new Request.Builder()
                        .url(packet.getTargetURL())
                        .post(body)
                        .build();
            } else {
                body = null;
                request = new Request.Builder()
                        .url(packet.getTargetURL())
                        .build();
            }
            Timber.tag(LOGGER_TAG).d("request: %s", request.toString());
            if (body != null) {
                Timber.tag(LOGGER_TAG).d("body: %s", packet.getJSONObject().toString());
            }
            Response response = client.newCall(request).execute();

            int statusCode = response.code();
            response.close();
            Timber.tag(LOGGER_TAG).d("status code %s", statusCode);
            return statusCode == HttpURLConnection.HTTP_NO_CONTENT || statusCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            // Broad but an analytics app shouldn't impact it's host app.
            Timber.tag(LOGGER_TAG).w(e, "Cannot send request");
        }
        return false;
    }

    public List<Packet> getDryRunOutput() {
        return mDryRunOutput;
    }
}
