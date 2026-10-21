package com.ansub.tvhome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class SourcesTest {
    static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/fixtures", name)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // wikimediaKeepsOnlyScreenShaped
        List<Picture> wm = Sources.parseWikimedia(fixture("wikimedia-landscapes.json"), "landscapes");
        Check.yes(!wm.isEmpty(), "wikimedia parsed some");
        // 8916x1273 banner, 6591x942 banner, 21919x4409 panorama, 3311x5056 portrait, and a subcategory page
        for (Picture p : wm) {
            for (String bad : new String[] {"wm:199762819", "wm:168762352", "wm:150457900", "wm:155367517", "wm:199181121"}) {
                Check.yes(!p.id.equals(bad), "unfit picture kept: " + p.id + " " + p.title);
            }
        }
        boolean sCharl = false;
        for (Picture p : wm) if (p.id.equals("wm:200914888")) sCharl = true;
        Check.yes(sCharl, "S-charl 5184x3456 kept");

        // wikimediaStripsHtmlAuthor + wikimediaFieldsSet
        for (Picture p : wm) {
            Check.yes(!p.author.contains("<") && !p.author.contains("  "), "clean author: " + p.author);
            Check.yes(p.id.startsWith("wm:"), "wm id");
            Check.yes(p.file.startsWith("https://"), "thumb url");
            Check.yes(!p.licence.isEmpty(), "licence");
            Check.eq("landscapes", p.category, "category");
            Check.eq("Wikimedia Commons", p.source, "source");
            Check.yes(p.fresh && !p.art, "flags");
            Check.yes(!p.title.startsWith("File:") && !p.title.endsWith(".jpg"), "title cleaned: " + p.title);
        }

        // metSearchIds
        List<Integer> ids = Sources.parseMetSearch(fixture("met-search.json"));
        Check.yes(!ids.isEmpty(), "met ids");
        Check.eq(340468, ids.get(0), "first met id");

        // metObjectPublicDomain
        Picture met = Sources.parseMetObject(fixture("met-object.json"));
        Check.yes(met != null, "met object parsed");
        Check.eq("Landscape", met.title, "met title");
        Check.eq("Ralph Albert Blakelock", met.author, "met artist");
        Check.yes(met.file.endsWith("ap29.35.jpg"), "met image: " + met.file);
        Check.eq("met:10181", met.id, "met id");
        Check.eq("Public domain", met.licence, "met licence");
        Check.eq("The Met", met.source, "met source");
        Check.yes(met.art && met.fresh, "met flags");
        Check.eq("art", met.category, "met category");

        // metObjectNotPublicDomainIsNull
        Check.eq(null, Sources.parseMetObject(fixture("met-object-not-pd.json")), "not public domain");

        // parsersRejectGarbage
        Check.yes(Sources.parseWikimedia("<html>", "landscapes").isEmpty(), "html");
        Check.yes(Sources.parseWikimedia("{}", "landscapes").isEmpty(), "empty object");
        Check.yes(Sources.parseWikimedia(null, "landscapes").isEmpty(), "null");
        Check.yes(Sources.parseMetSearch("").isEmpty(), "empty search");
        Check.yes(Sources.parseMetSearch("{\"total\":0,\"objectIDs\":null}").isEmpty(), "no ids");
        Check.eq(null, Sources.parseMetObject("null"), "null object");
        Check.eq(null, Sources.parseMetObject("{\"isPublicDomain\":true,\"primaryImageSmall\":\"\"}"), "no image");

        // urlsExact
        Check.eq("https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers"
                + "&gcmtitle=Category:Featured_pictures_of_cityscapes&gcmtype=file&gcmlimit=50&gcmsort=timestamp&gcmdir=desc"
                + "&prop=imageinfo&iiprop=size|url|extmetadata&iiurlwidth=1280"
                + "&iiextmetadatafilter=LicenseShortName|Artist|ObjectName&format=json",
                Sources.wikimediaUrl("Featured_pictures_of_cityscapes"), "wikimedia url");
        Check.yes(Sources.metSearchUrl("city view").contains("q=city+view"), "met query encoded");
        Check.yes(Sources.metSearchUrl("x").startsWith("https://collectionapi.metmuseum.org/public/collection/v1.1/search?hasImages=true&isPublicDomain=true"), "met search url");
        Check.eq("https://collectionapi.metmuseum.org/public/collection/v1/objects/10181", Sources.metObjectUrl(10181), "met object url");
        Check.eq("tvhome/1.5 (Android TV home screen; https://github.com/Ansub/tv-detox)", Sources.UA, "user agent");

        // unsplashParsesRandomPhotos
        List<Sources.Found> us = Sources.parseUnsplash(fixture("unsplash-random.json"), "landscapes", 1280, 720);
        Check.eq(8, us.size(), "unsplash photos");
        Sources.Found first = us.get(0);
        Check.eq("us:ucYWe5mzTMU", first.picture.id, "unsplash id");
        Check.eq("Jeremy Cai", first.picture.author, "unsplash author");
        Check.eq("Bird's eye view of grassland beside mountain", first.picture.title, "title capitalised");
        Check.eq("Unsplash License", first.picture.licence, "unsplash licence");
        Check.eq("Unsplash", first.picture.source, "unsplash source");
        Check.eq("landscapes", first.picture.category, "unsplash category");
        Check.yes(first.picture.fresh && !first.picture.art, "unsplash flags");
        Check.yes(first.picture.file.startsWith("https://images.unsplash.com/"), "image url");
        Check.yes(first.picture.file.endsWith("&w=1280&h=720&fit=crop&crop=entropy&q=80&fm=jpg"), "zoom to fill: " + first.picture.file);
        Check.yes(first.ping.startsWith("https://api.unsplash.com/photos/ucYWe5mzTMU/download"), "download ping");
        Check.yes(Sources.parseUnsplash("{\"errors\":[\"OAuth error\"]}", "art", 1280, 720).isEmpty(), "unsplash error reply");
        Check.yes(Sources.parseUnsplash("<html>", "art", 1280, 720).isEmpty(), "unsplash garbage");

        // unsplashParsesIllustrations (search results: the {"results": [...]} shape)
        List<Sources.Found> found = Sources.parseUnsplash(fixture("unsplash-illustrations.json"), "abstract", 1280, 720);
        Check.eq(20, found.size(), "illustration results");
        Check.eq("us:MQOsokZW2Mw", found.get(0).picture.id, "illustration id");
        Check.eq("Abstract swirling patterns in shades of blue", found.get(0).picture.title, "illustration title");
        Check.eq("abstract", found.get(0).picture.category, "illustration category");
        Check.eq("https://api.unsplash.com/search/illustrations?query=abstract+background&orientation=landscape&content_filter=high&page=3&per_page=2",
                Sources.unsplashIllustrationsUrl("abstract background", 3, 2), "illustrations url");

        // unsplashUrl
        Check.eq("https://api.unsplash.com/photos/random?query=city+skyline&orientation=landscape&content_filter=high&count=2",
                Sources.unsplashUrl("city skyline", 2), "unsplash url");
    }
}
