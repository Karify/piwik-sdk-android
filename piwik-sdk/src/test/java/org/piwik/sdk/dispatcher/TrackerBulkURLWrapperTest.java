package org.piwik.sdk.dispatcher;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.piwik.sdk.QueryParams;
import org.piwik.sdk.TrackMe;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


@Config(emulateSdk = 18, manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class TrackerBulkURLWrapperTest {

    private TrackerBulkURLWrapper createWrapper(String url, TrackMe... events) throws Exception {
        if (url == null) {
            url = "http://example.com/";
        }
        HttpUrl _url = HttpUrl.parse(url);

        return new TrackerBulkURLWrapper(_url, Arrays.asList(events), "test_token");
    }

    @Test
    public void testEmptyIterator() throws Exception {
        TrackerBulkURLWrapper wrapper = createWrapper(null);
        assertFalse(wrapper.iterator().hasNext());
        assertNull(wrapper.iterator().next());
    }

    @Test
    public void testEmptyPage() throws Exception {
        TrackerBulkURLWrapper wrapper = createWrapper(null);
        assertNull(wrapper.getEvents(null));
    }

    @Test
    public void testEventUrlForEmptyPage() throws Exception {
        TrackerBulkURLWrapper wrapper = createWrapper(null);
        assertNull(wrapper.getEventUrl(null));
    }

    @Test
    public void testWrapperMalformedURLException() throws Exception {
        TrackerBulkURLWrapper wrapper = createWrapper("http://localhost", new TrackMe());
        assertNull(wrapper.getEventUrl(wrapper.iterator().next()));
    }

    @Test
    public void testPageIterator() throws Exception {
        TrackerBulkURLWrapper wrapper = createWrapper(null, new TrackMe());
        assertTrue(wrapper.iterator().hasNext());
        assertEquals(wrapper.iterator().next().elementsCount(), 1);
        assertNull(wrapper.iterator().next());
    }

    @Test
    public void testPage() throws Exception {
        List<TrackMe> events = new LinkedList<TrackMe>();
        for (int i = 0; i < TrackerBulkURLWrapper.getEventsPerPage() * 2; i++) {
            events.add(new TrackMe().set(QueryParams.ACTION_NAME, 1));
        }
        TrackerBulkURLWrapper wrapper = new TrackerBulkURLWrapper(HttpUrl.parse("http://example.com/"), events, null);

        Iterator<TrackerBulkURLWrapper.Page> it = wrapper.iterator();
        assertTrue(it.hasNext());
        while (it.hasNext()) {
            TrackerBulkURLWrapper.Page page = it.next();
            assertEquals(page.elementsCount(), TrackerBulkURLWrapper.getEventsPerPage());
            JSONArray requests = wrapper.getEvents(page).getJSONArray("requests");
            assertEquals(requests.length(), TrackerBulkURLWrapper.getEventsPerPage());
            assertTrue(requests.get(0).toString().startsWith("eve"));
            assertTrue(requests.get(TrackerBulkURLWrapper.getEventsPerPage() - 1).toString().length() >= 4);
            assertFalse(page.isEmpty());
        }
        assertFalse(it.hasNext());
        assertNull(it.next());
    }

    @Test
    public void testGetApiUrl() throws Exception {
        String url = "http://www.com/java.htm";
        TrackerBulkURLWrapper wrapper = createWrapper(url, new TrackMe());
        assertEquals(wrapper.getApiUrl().toString(), url);
    }

    @Test
    public void testGetEvents() throws Exception {
        TrackMe one = new TrackMe().trySet(QueryParams.ACTION_NAME, "one");
        TrackMe two = new TrackMe().trySet(QueryParams.ACTION_NAME, "two");
        TrackerBulkURLWrapper wrapper = createWrapper(null, one, two);
        TrackerBulkURLWrapper.Page page = wrapper.iterator().next();

        assertEquals(wrapper.getEvents(page).getJSONArray("requests").length(), 2);
        assertEquals(wrapper.getEvents(page).getJSONArray("requests").get(0), "?one=1");
        assertEquals(wrapper.getEvents(page).getJSONArray("requests").get(1), "?two=2");
        assertEquals(wrapper.getEvents(page).getString("token_auth"), "test_token");
    }

    @Test
    public void testGetEventUrl() throws Exception {
        List<TrackMe> events = new LinkedList<TrackMe>();
        for (int i = 0; i < TrackerBulkURLWrapper.getEventsPerPage() + 1; i++) {
            events.add(new TrackMe().trySet(QueryParams.ACTION_NAME, "eve" + i));
        }
        HttpUrl url = HttpUrl.parse("http://example.com/");
        TrackerBulkURLWrapper wrapper = new TrackerBulkURLWrapper(url, events, null);
        //skip first page
        wrapper.iterator().next();

        //get second with only element
        TrackerBulkURLWrapper.Page page = wrapper.iterator().next();
        assertEquals(page.elementsCount(), 1);
        assertFalse(page.isEmpty());
        assertEquals(wrapper.getEventUrl(page), new URL("http://example.com/?eve20"));
    }
}