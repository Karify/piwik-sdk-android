/*
 * Android SDK for Piwik
 *
 * @link https://github.com/piwik/piwik-android-sdk
 * @license https://github.com/piwik/piwik-sdk-android/blob/master/LICENSE BSD-3 Clause
 */

package org.piwik.sdk.dispatcher;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.piwik.sdk.Piwik;
import org.piwik.sdk.QueryParams;
import org.piwik.sdk.TrackMe;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import timber.log.Timber;


public class TrackerBulkURLWrapper {
    private static final String LOGGER_TAG = Piwik.LOGGER_PREFIX + "TrackerBulkURLWrapper";
    private static final int EVENTS_PER_PAGE = 20;
    private int mCurrentPage = 0;
    private final int mPages;
    private final HttpUrl mHttpUrl;
    private final String mAuthtoken;
    private final List<TrackMe> mTrackMes;

    public TrackerBulkURLWrapper(@NonNull final HttpUrl httpUrl, @NonNull final List<TrackMe> trackMes, @Nullable final String authToken) {
        mHttpUrl = httpUrl;
        mAuthtoken = authToken;
        mPages = (int) Math.ceil(trackMes.size() * 1.0 / EVENTS_PER_PAGE);
        mTrackMes = trackMes;
    }

    protected static int getEventsPerPage() {
        return EVENTS_PER_PAGE;
    }

    /**
     * page iterator
     *
     * @return iterator
     */
    public Iterator<Page> iterator() {
        return new Iterator<Page>() {
            @Override
            public boolean hasNext() {
                return mCurrentPage < mPages;
            }

            @Override
            public Page next() {
                if (hasNext()) {
                    return new Page(mCurrentPage++);
                }
                return null;
            }

            @Override
            public void remove() {
            }
        };
    }

    @NonNull
    public HttpUrl getApiUrl() {
        return mHttpUrl;
    }

    /**
     * {
     * "requests": ["?idsite=1&url=http://example.org&action_name=Test bulk log Pageview&rec=1",
     * "?idsite=1&url=http://example.net/test.htm&action_name=Another bul k page view&rec=1"],
     * "token_auth": "33dc3f2536d3025974cccb4b4d2d98f4"
     * }
     *
     * @return json object
     */
    @Nullable
    public JSONObject getEvents(Page page) {
        if (page == null || page.isEmpty()) {
            return null;
        }

        List<TrackMe> pageElements = mTrackMes.subList(page.fromIndex, page.toIndex);

        if (pageElements.size() == 0) {
            Timber.tag(LOGGER_TAG).w("Empty page");
            return null;
        }

        JSONObject params = new JSONObject();
        try {
            params.put("requests", new JSONArray(pageElements));

            if (mAuthtoken != null) {
                params.put(QueryParams.AUTHENTICATION_TOKEN.toString(), mAuthtoken);
            }
        } catch (JSONException e) {
            Timber.tag(LOGGER_TAG).w(e, "Cannot create json object:\n%s", TextUtils.join(", ", pageElements));
            return null;
        }
        return params;
    }

    /**
     * @param page Page object
     * @return tracked url. For example
     * "http://domain.com/piwik.php?idsite=1&url=http://a.org&action_name=Test bulk log Pageview&rec=1"
     */
    @Nullable
    public HttpUrl getEventUrl(Page page) {
        if (page == null || page.isEmpty()) {
            return null;
        }

        final HttpUrl.Builder builder = mHttpUrl.newBuilder();
        final Map<String, String> params = mTrackMes.get(page.fromIndex).toMap();

        for (Map.Entry<String, String> queryParams : params.entrySet()) {
            builder.addQueryParameter(queryParams.getKey(), queryParams.getValue());
        }

        return builder.build();
    }

    public final class Page {

        protected final int fromIndex, toIndex;

        protected Page(int pageNumber) {
            if (!(pageNumber >= 0 || pageNumber < mPages)) {
                fromIndex = toIndex = -1;
                return;
            }
            fromIndex = pageNumber * EVENTS_PER_PAGE;
            toIndex = Math.min(fromIndex + EVENTS_PER_PAGE, mTrackMes.size());
        }

        public int elementsCount() {
            return toIndex - fromIndex;
        }

        public boolean isEmpty() {
            return fromIndex == -1 || elementsCount() == 0;
        }
    }

}
