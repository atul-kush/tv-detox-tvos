package com.ansub.tvhome;

// One background picture and what the credit line needs. Built-in ones live in assets/walls/
// (fresh=false), downloaded ones in the cache (fresh=true, added = when it arrived).
final class Picture {
    final String id, file, title, author, licence, source, category;
    final boolean art, fresh;
    final long added;

    Picture(String id, String file, String title, String author, String licence, String source, String category,
            boolean art, boolean fresh, long added) {
        this.id = id;
        this.file = file;
        this.title = title;
        this.author = author;
        this.licence = licence;
        this.source = source;
        this.category = category;
        this.art = art;
        this.fresh = fresh;
        this.added = added;
    }

    // One line of assets/walls/index.tsv or a cache sidecar file; null if it can't be read.
    static Picture fromTsv(String line) {
        if (line == null) return null;
        String[] f = line.split("\t", -1);
        if (f.length < 10) return null;
        try {
            return new Picture(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7].equals("1"), f[8].equals("1"), Long.parseLong(f[9].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String toTsv() {
        return clean(id) + "\t" + clean(file) + "\t" + clean(title) + "\t" + clean(author) + "\t" + clean(licence) + "\t"
                + clean(source) + "\t" + clean(category) + "\t" + (art ? "1" : "0") + "\t" + (fresh ? "1" : "0") + "\t" + added;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
