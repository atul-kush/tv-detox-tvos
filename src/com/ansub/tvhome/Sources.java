package com.ansub.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Where fresh pictures come from, and how to read the answers. With an Unsplash key (unsplash.key,
// built in by build-local.sh), every theme comes from Unsplash. Without one: Wikimedia Commons'
// featured pictures (landscapes, cities, space) and The Met's public-domain paintings (art), no key needed.
// Every parser returns nothing rather than throwing, so a bad answer just means no new picture.
final class Sources {
    static final String UA = "tvhome/1.5 (Android TV home screen; https://github.com/Ansub/tv-detox)";

    private Sources() {}

    static String wikimediaUrl(String commonsCategory) {
        return "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers"
                + "&gcmtitle=Category:" + commonsCategory + "&gcmtype=file&gcmlimit=50&gcmsort=timestamp&gcmdir=desc"
                + "&prop=imageinfo&iiprop=size|url|extmetadata&iiurlwidth=1280"
                + "&iiextmetadatafilter=LicenseShortName|Artist|ObjectName&format=json";
    }

    // Screen-shaped files with a 1280 px version and a licence that allows showing them anywhere.
    static List<Picture> parseWikimedia(String json, String category) {
        List<Picture> out = new ArrayList<>();
        try {
            JSONObject pages = new JSONObject(json).getJSONObject("query").getJSONObject("pages");
            for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
                JSONObject page = pages.getJSONObject(it.next());
                JSONArray infos = page.optJSONArray("imageinfo");
                if (infos == null || infos.length() == 0) continue;
                JSONObject info = infos.getJSONObject(0);
                String thumb = info.optString("thumburl", "");
                if (!PictureRules.fitsScreen(info.optInt("width"), info.optInt("height")) || !thumb.startsWith("https://")) continue;
                JSONObject meta = info.optJSONObject("extmetadata");
                String licence = meta(meta, "LicenseShortName");
                if (licence.isEmpty() || licence.contains("NC") || licence.contains("ND")) continue;
                String title = meta(meta, "ObjectName");
                if (title.isEmpty()) title = fileTitle(page.optString("title", ""));
                out.add(new Picture("wm:" + page.getLong("pageid"), thumb, title, meta(meta, "Artist"), licence,
                        "Wikimedia Commons", category, false, true, 0));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    // An Unsplash photo and the address Unsplash asks to be told about when the photo is used.
    static final class Found {
        final Picture picture;
        final String ping;

        Found(Picture picture, String ping) {
            this.picture = picture;
            this.ping = ping;
        }
    }

    static String unsplashUrl(String query, int count) {
        try {
            return "https://api.unsplash.com/photos/random?query=" + URLEncoder.encode(query, "UTF-8")
                    + "&orientation=landscape&content_filter=high&count=" + count;
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    // Unsplash's illustrations (not photos), searched one page at a time.
    static String unsplashIllustrationsUrl(String query, int page, int perPage) {
        try {
            return "https://api.unsplash.com/search/illustrations?query=" + URLEncoder.encode(query, "UTF-8")
                    + "&orientation=landscape&content_filter=high&page=" + page + "&per_page=" + perPage;
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    // Photos from /photos/random or /search/photos ({"results": [...]}), each cropped by Unsplash to exactly w x h (zoomed to fill, keeping the
    // busiest part), so the TV downloads a ready-made screen-sized picture.
    static List<Found> parseUnsplash(String json, String category, int w, int h) {
        List<Found> out = new ArrayList<>();
        try {
            String trimmed = json.trim();
            JSONArray photos = trimmed.startsWith("{") ? new JSONObject(trimmed).getJSONArray("results") : new JSONArray(trimmed);
            for (int i = 0; i < photos.length(); i++) {
                JSONObject p = photos.getJSONObject(i);
                String raw = p.getJSONObject("urls").optString("raw", "");
                String ping = p.getJSONObject("links").optString("download_location", "");
                if (!raw.startsWith("https://") || !ping.startsWith("https://")) continue;
                String title = p.optString("alt_description", "");
                if (title.isEmpty() || title.equals("null")) title = p.optString("description", "");
                if (title.isEmpty() || title.equals("null")) title = "Untitled";
                title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
                String file = raw + (raw.contains("?") ? "&" : "?") + "w=" + w + "&h=" + h + "&fit=crop&crop=entropy&q=80&fm=jpg";
                Picture pic = new Picture("us:" + p.getString("id"), file, plain(title),
                        plain(p.getJSONObject("user").optString("name", "")), "Unsplash License", "Unsplash", category,
                        false, true, 0);
                out.add(new Found(pic, ping));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    static String metSearchUrl(String query) {
        try {
            return "https://collectionapi.metmuseum.org/public/collection/v1.1/search?hasImages=true&isPublicDomain=true&q="
                    + URLEncoder.encode(query, "UTF-8") + "&limit=40";
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static List<Integer> parseMetSearch(String json) {
        List<Integer> out = new ArrayList<>();
        try {
            JSONArray ids = new JSONObject(json).optJSONArray("objectIDs");
            if (ids != null) for (int i = 0; i < ids.length(); i++) out.add(ids.getInt(i));
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    static String metObjectUrl(int id) {
        return "https://collectionapi.metmuseum.org/public/collection/v1/objects/" + id;
    }

    // A painting we may show without credit, or null.
    static Picture parseMetObject(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String image = o.optString("primaryImageSmall", "");
            if (!o.optBoolean("isPublicDomain") || image.isEmpty()) return null;
            return new Picture("met:" + o.getInt("objectID"), image, plain(o.optString("title", "")),
                    plain(o.optString("artistDisplayName", "")), "Public domain", "The Met", "art", true, true, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String meta(JSONObject meta, String key) {
        JSONObject field = meta == null ? null : meta.optJSONObject(key);
        return field == null ? "" : plain(field.optString("value", ""));
    }

    // Artist and title fields are HTML: keep the words only.
    private static String plain(String html) {
        return html.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String fileTitle(String pageTitle) {
        String t = pageTitle.startsWith("File:") ? pageTitle.substring(5) : pageTitle;
        int dot = t.lastIndexOf('.');
        return dot > 0 ? t.substring(0, dot) : t;
    }
}
