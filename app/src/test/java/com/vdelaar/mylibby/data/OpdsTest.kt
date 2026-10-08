package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.network.fillSearchTemplate
import com.vdelaar.mylibby.core.network.opdsFormatFor
import com.vdelaar.mylibby.core.network.parseOpdsFeed
import com.vdelaar.mylibby.core.network.parseOpenSearchTemplate
import com.vdelaar.mylibby.core.network.resolveOpdsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsParserTest {
    private val root = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dc="http://purl.org/dc/terms/" xmlns:os="http://a9.com/-/spec/opensearch/1.1/">
  <id>urn:root</id>
  <title>My Calibre library</title>
  <link rel="self" href="/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  <link rel="start" href="/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  <link rel="search" href="/opds/osd" type="application/opensearchdescription+xml"/>
  <entry>
    <title>Recently added</title>
    <id>urn:new</id>
    <updated>2026-10-01T00:00:00Z</updated>
    <content type="text">The newest books</content>
    <link rel="http://opds-spec.org/sort/new" href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  </entry>
  <entry>
    <title>Authors</title>
    <id>urn:authors</id>
    <link rel="subsection" href="authors/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  </entry>
</feed>"""

    @Test fun readsNavigationEntriesAndResolvesRelativeLinks() {
        val f = parseOpdsFeed(root, "http://calibre.local:8080/opds")
        assertEquals("My Calibre library", f.title)
        assertEquals(2, f.entries.size)
        assertEquals("http://calibre.local:8080/opds/new", f.entries[0].navigationUrl)
        assertEquals("http://calibre.local:8080/authors/", f.entries[1].navigationUrl)
        assertEquals("The newest books", f.entries[0].summary)
        assertTrue(f.entries.none { it.isBook })
        assertEquals("http://calibre.local:8080/opds/osd", f.searchDescriptionUrl)
        assertNull(f.nextUrl)
    }

    private val books = """<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
  <title>Recently added</title>
  <link rel="next" href="/opds/new?offset=2" type="application/atom+xml;profile=opds-catalog"/>
  <entry>
    <id>urn:book:1</id>
    <title>Dune</title>
    <author><name>Frank Herbert</name></author>
    <dc:language>en</dc:language>
    <summary>Desert planet &amp; spice.</summary>
    <link rel="http://opds-spec.org/image" href="/cover/1.jpg" type="image/jpeg"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="/thumb/1.jpg" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition" href="/get/1.epub" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/acquisition" href="/get/1.pdf" type="application/pdf"/>
    <link rel="alternate" href="/entry/1" type="application/atom+xml;type=entry;profile=opds-catalog"/>
  </entry>
  <entry>
    <id>urn:book:2</id>
    <title>Hyperion</title>
    <author><name>Dan Simmons</name></author>
    <author><name>Second Author</name></author>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Seven <b>pilgrims</b>.</p><p>One shrike.</p></div></content>
    <link rel="http://opds-spec.org/acquisition/open-access" href="https://cdn.example/h.epub" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/acquisition/sample" href="/sample/2.epub" type="application/epub+zip"/>
  </entry>
</feed>"""

    @Test fun readsBooksWithFormatsCoversAndAuthors() {
        val f = parseOpdsFeed(books, "http://calibre.local:8080/opds/new")
        assertEquals("http://calibre.local:8080/opds/new?offset=2", f.nextUrl)
        val dune = f.entries[0]
        assertTrue(dune.isBook)
        assertNull("a book's own detail link is not a folder", dune.navigationUrl)
        assertEquals(listOf("Frank Herbert"), dune.authors)
        assertEquals("en", dune.language)
        assertEquals("Desert planet & spice.", dune.summary)
        assertEquals("http://calibre.local:8080/cover/1.jpg", dune.coverUrl)
        assertEquals("http://calibre.local:8080/thumb/1.jpg", dune.thumbnailUrl)
        assertEquals(2, dune.acquisitions.size)
        assertEquals(listOf("EPUB"), dune.readable.map { it.format })
        assertEquals("http://calibre.local:8080/get/1.epub", dune.readable.single().url)
    }

    @Test fun readsXhtmlSummariesMultipleAuthorsAndIgnoresSamples() {
        val h = parseOpdsFeed(books, "http://x/opds").entries[1]
        assertEquals(listOf("Dan Simmons", "Second Author"), h.authors)
        assertEquals("Seven pilgrims . One shrike.".replace(" .", "."), h.summary!!.replace(" .", "."))
        assertEquals(1, h.acquisitions.size)
        assertEquals("open-access", h.acquisitions.single().kind)
        assertEquals("https://cdn.example/h.epub", h.acquisitions.single().url)
        // no cover link: the thumbnail falls back to nothing, not to garbage
        assertNull(h.coverUrl)
    }

    @Test fun searchLinkGivenDirectlyAsATemplate() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title>
            <link rel="search" type="application/atom+xml" href="/opds/search?q={searchTerms}"/></feed>"""
        val f = parseOpdsFeed(xml, "http://h/opds")
        assertEquals("http://h/opds/search?q={searchTerms}", f.searchTemplate)
        assertNull(f.searchDescriptionUrl)
    }

    @Test fun emptyOrForeignXmlGivesAnEmptyFeed() {
        val f = parseOpdsFeed("""<rss version="2.0"><channel><title>x</title></channel></rss>""", "http://h/")
        assertTrue(f.entries.isEmpty())
    }

    @Test(expected = Exception::class) fun brokenXmlThrows() {
        parseOpdsFeed("<feed><entry>", "http://h/")
    }

    @Test fun doesNotLoadExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>&x;</title></feed>"""
        val title = runCatching { parseOpdsFeed(evil, "http://h/").title }.getOrDefault("")
        assertFalse(title.contains("root:"))
    }
}

class OpdsSearchTest {
    @Test fun openSearchDescriptionYieldsTheAtomTemplate() {
        val xml = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <ShortName>Calibre</ShortName>
            <Url type="text/html" template="http://h/search?query={searchTerms}"/>
            <Url type="application/atom+xml" template="http://h/opds/search/{searchTerms}"/>
        </OpenSearchDescription>"""
        assertEquals("http://h/opds/search/{searchTerms}", parseOpenSearchTemplate(xml))
        assertNull(parseOpenSearchTemplate("<OpenSearchDescription xmlns=\"http://a9.com/-/spec/opensearch/1.1/\"><Url type=\"text/html\" template=\"x\"/></OpenSearchDescription>"))
    }

    @Test fun fillsTemplatesAndDropsOptionalParts() {
        assertEquals("http://h/opds/search/de%20bewegende%20kaart", fillSearchTemplate("http://h/opds/search/{searchTerms}", "de bewegende kaart"))
        assertEquals("http://h/s?q=dune&count=20".replace("&count=20", ""), fillSearchTemplate("http://h/s?q={searchTerms}&startPage={startPage?}", "dune"))
        assertEquals("http://h/s?q=a%26b", fillSearchTemplate("http://h/s?q={searchTerms}", "a&b"))
    }

    @Test fun resolvesLinks() {
        assertEquals("http://h/a/b", resolveOpdsUrl("http://h/a/c", "b"))
        assertEquals("http://h/b", resolveOpdsUrl("http://h/a/c", "/b"))
        assertEquals("https://other/x", resolveOpdsUrl("http://h/a", "https://other/x"))
    }

    @Test fun mapsFormats() {
        assertEquals("EPUB", opdsFormatFor("application/epub+zip"))
        assertEquals("EPUB", opdsFormatFor("application/octet-stream", "http://h/get/12.epub?download=1"))
        assertEquals("MOBI", opdsFormatFor("application/x-mobipocket-ebook"))
        assertNull(opdsFormatFor("application/pdf", "http://h/x.pdf"))
    }
}
