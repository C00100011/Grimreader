// Bridge between the Android app and foliate-js.
// Kotlin -> JS: window.reader.<command>(...) via evaluateJavascript.
// JS -> Kotlin: MyLibby.post(type, json) via @JavascriptInterface.
import './foliate/view.js'
import { Overlayer } from './foliate/overlayer.js'

const post = (type, payload = {}) => {
    try {
        globalThis.MyLibby?.post(type, JSON.stringify(payload))
    } catch (e) {
        console.error('post failed', type, e)
    }
}

const FONT_FILES = {
    Literata: 'Literata',
    Merriweather: 'Merriweather',
    Lora: 'Lora',
    'Atkinson Hyperlegible': 'AtkinsonHyperlegible',
    OpenDyslexic: 'OpenDyslexic',
}

const fontFaces = () => {
    const base = new URL('fonts/', location.href).href
    return Object.entries(FONT_FILES).map(([family, file]) => `
        @font-face { font-family: "${family}"; font-weight: 400; font-style: normal; src: url("${base}${file}-400-normal.woff2") format("woff2"); }
        @font-face { font-family: "${family}"; font-weight: 700; font-style: normal; src: url("${base}${file}-700-normal.woff2") format("woff2"); }
        @font-face { font-family: "${family}"; font-weight: 400; font-style: italic; src: url("${base}${file}-400-italic.woff2") format("woff2"); }
    `).join('\n')
}

const fontStack = family => {
    switch (family) {
        case 'publisher': return null
        case 'serif': return 'serif'
        case 'sans-serif': return 'sans-serif'
        default: return `"${family}", serif`
    }
}

// Guided colours: a left-to-right colour gradient over every line, to lead the eyes along the text.
const PALETTES = [['#1d4ed8', '#0d9488'], ['#c2410c', '#be185d'], ['#15803d', '#a16207'], ['#6d28d9', '#0369a1']]

const hexToRgb = h => {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/i.exec(h ?? '')
    return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [34, 34, 34]
}

const mixColors = (a, b, t) => {
    const [r1, g1, b1] = hexToRgb(a)
    const [r2, g2, b2] = hexToRgb(b)
    const c = (x, y) => Math.round(x + (y - x) * t).toString(16).padStart(2, '0')
    return `#${c(r1, r2)}${c(g1, g2)}${c(b1, b2)}`
}

const guideCSS = s => {
    if (!s.guided) return ''
    const pair = PALETTES[(s.guidePalette ?? 0) % PALETTES.length]
    const t = Math.min(1, Math.max(0, s.guideIntensity ?? 0.6))
    // On dark pages the palette is lightened so it keeps its contrast.
    const tone = c => s.theme.dark ? mixColors(c, '#ffffff', 0.45) : c
    const from = mixColors(s.theme.fg, tone(pair[0]), t)
    const to = mixColors(s.theme.fg, tone(pair[1]), t)
    return `
    @supports (-webkit-background-clip: text) {
        p, li, dd, h1, h2, h3, h4, h5, h6 {
            background-image: linear-gradient(90deg, ${from}, ${to}) !important;
            -webkit-background-clip: text !important;
            background-clip: text !important;
            -webkit-text-fill-color: transparent !important;
        }
    }`
}

const getCSS = s => {
    const stack = fontStack(s.fontFamily)
    return `
    @namespace epub "http://www.idpf.org/2007/ops";
    ${fontFaces()}
    html {
        color: ${s.theme.fg} !important;
        background: transparent !important;
        font-size: ${s.fontSizePx}px !important;
        -webkit-text-size-adjust: none;
    }
    body {
        color: ${s.theme.fg} !important;
        background: transparent !important;
        ${stack ? `font-family: ${stack} !important;` : ''}
    }
    ${stack ? `body *:not(code):not(pre):not(kbd):not(samp) { font-family: inherit !important; }` : ''}
    /* Publisher colours can be unreadable on our page colours: always use the theme text colour. */
    body *:not(a) { color: inherit !important; }
    ${s.theme.dark ? 'body * { background-color: transparent !important; border-color: currentColor !important; }' : ''}
    p, li, blockquote, dd, div {
        line-height: ${s.lineHeight} !important;
    }
    p, li, blockquote, dd {
        text-align: ${s.justify ? 'justify' : 'start'};
        -webkit-hyphens: ${s.hyphenate ? 'auto' : 'manual'};
        hyphens: ${s.hyphenate ? 'auto' : 'manual'};
        -webkit-hyphenate-limit-before: 3;
        -webkit-hyphenate-limit-after: 2;
        -webkit-hyphenate-limit-lines: 2;
        widows: 2;
        orphans: 2;
    }
    p { margin-block-end: ${s.paragraphSpacing}em; }
    [align="left"] { text-align: left; }
    [align="right"] { text-align: right; }
    [align="center"] { text-align: center; }
    [align="justify"] { text-align: justify; }
    a:link, a:visited { color: ${s.theme.link} !important; }
    pre { white-space: pre-wrap !important; }
    ::selection { background: ${s.theme.selection}; }
    ${s.theme.dark ? 'img, svg { filter: brightness(.85); }' : ''}
    aside[epub|type~="endnote"],
    aside[epub|type~="footnote"],
    aside[epub|type~="note"],
    aside[epub|type~="rearnote"] {
        display: none;
    }
    ${guideCSS(s)}
`
}

const flattenTOC = (items, depth = 0, out = []) => {
    for (const item of items ?? []) {
        out.push({ label: (item.label ?? '').trim(), href: item.href ?? '', depth })
        if (item.subitems?.length) flattenTOC(item.subitems, depth + 1, out)
    }
    return out
}

const formatLanguageMap = x => {
    if (!x) return ''
    if (typeof x === 'string') return x
    const keys = Object.keys(x)
    return x[keys[0]]
}

const formatContributor = c => {
    if (!c) return ''
    if (Array.isArray(c)) return c.map(formatContributor).filter(Boolean).join(', ')
    return typeof c === 'string' ? c : formatLanguageMap(c?.name)
}

class Reader {
    view = null
    settings = null
    annotations = new Map() // cfi -> annotation {value, color, style, note}
    annotationIndex = new Map() // cfi -> section index
    docs = new Map() // doc -> section index
    ttsActive = false
    ttsGranularity = 'sentence'
    selectionTimer = null
    lastSelection = null
    totalBytes = 0
    speed = null // { tokens, index, doc } of the section being speed-read

    async open(url, name, initialCfi, settings, annotations) {
        try {
            this.settings = settings
            this.applyPageBackground()
            const res = await fetch(url)
            if (!res.ok) throw new Error(`Could not load book (${res.status})`)
            const blob = await res.blob()
            const file = new File([blob], name || 'book.epub')

            this.view = document.createElement('foliate-view')
            document.body.append(this.view)
            this.view.addEventListener('load', e => this.#onLoad(e.detail))
            // Taps beside/above/below the text column (wide screens, header/footer strips) land on the page host, not
            // in the book's own frame: handle them with the same zones so the whole screen turns pages.
            document.addEventListener('click', e => this.#onOuterClick(e), false)
            this.view.addEventListener('relocate', e => this.#onRelocate(e.detail))
            this.view.addEventListener('external-link', e => {
                e.preventDefault()
                post('externalLink', { href: e.detail.a?.href ?? '' })
            })
            this.view.addEventListener('create-overlay', e => this.#onCreateOverlay(e.detail))
            this.view.addEventListener('draw-annotation', e => this.#onDrawAnnotation(e.detail))
            this.view.addEventListener('show-annotation', e => {
                post('annotationClick', { cfi: e.detail.value })
            })

            await this.view.open(file)
            const { book } = this.view
            book.transformTarget?.addEventListener('data', ({ detail }) => {
                detail.data = Promise.resolve(detail.data).catch(err => {
                    console.error(new Error(`Failed to load ${detail.name}`, { cause: err }))
                    return ''
                })
            })
            for (const a of annotations ?? []) this.annotations.set(a.value, a)

            this.totalBytes = book.sections
                .filter(s => s.linear !== 'no')
                .reduce((sum, s) => sum + (s.size > 0 ? s.size : 0), 0)

            this.applySettings(settings)
            post('ready', {
                title: formatLanguageMap(book.metadata?.title) || name || '',
                author: formatContributor(book.metadata?.author),
                language: Array.isArray(book.metadata?.language)
                    ? book.metadata.language[0] ?? ''
                    : (book.metadata?.language ?? ''),
                toc: flattenTOC(book.toc),
                sectionCount: book.sections.length,
                totalBytes: this.totalBytes,
                sectionFractions: this.view.getSectionFractions?.() ?? [],
                fixedLayout: !!this.view.isFixedLayout,
            })
            // A stale or foreign position must never leave the reader blank.
            const fallback = async () => {
                if (this.relocated) return
                try { await this.view.goToTextStart() } catch (_) { await this.view.next() }
            }
            setTimeout(fallback, 4000)
            try {
                await this.view.init({ lastLocation: initialCfi || null, showTextStart: !initialCfi })
            } catch (e) {
                console.warn('Could not restore position', e)
                await fallback()
            }
        } catch (e) {
            console.error(e)
            post('error', { message: e?.message ?? String(e) })
        }
    }

    applyPageBackground() {
        const bg = this.settings?.theme?.bg ?? '#fbf6f0'
        document.documentElement.style.setProperty('--bg', bg)
        document.body.style.background = bg
    }

    applySettings(settings) {
        this.settings = settings
        this.measured?.clear()
        this.applyPageBackground()
        const r = this.view?.renderer
        if (!r) return
        r.setAttribute('flow', settings.flow === 'scrolled' ? 'scrolled' : 'paginated')
        r.setAttribute('gap', `${settings.marginPercent}%`)
        r.setAttribute('margin', `${settings.verticalMarginPx}px`)
        r.setAttribute('max-inline-size', `${settings.maxInlineSize}px`)
        r.setAttribute('max-column-count', `${settings.maxColumns}`)
        r.setAttribute('max-column-count-portrait', `${settings.portraitColumns ?? 1}`)
        r.setStyles?.(getCSS(settings))
    }

    #onLoad({ doc, index }) {
        this.docs.set(doc, index)
        doc.addEventListener('click', e => this.#onClick(e, doc), false)
        doc.addEventListener('selectionchange', () => this.#onSelectionChange(doc, index))
        doc.addEventListener('keydown', e => {
            if (e.key === 'ArrowLeft') this.view.goLeft()
            else if (e.key === 'ArrowRight') this.view.goRight()
        })
    }

    #onClick(e, doc) {
        if (e.defaultPrevented) return
        const sel = doc.getSelection()
        // A tap while text is selected only dismisses the selection (it must never leave taps dead).
        if (sel && !sel.isCollapsed) { sel.removeAllRanges(); return }
        if (e.target?.closest?.('a[href]')) return
        const frame = doc.defaultView?.frameElement
        const offset = frame ? frame.getBoundingClientRect().left : 0
        const x = offset + e.clientX
        const width = window.innerWidth
        const third = width / 3
        if (this.settings?.flow !== 'scrolled' && this.settings?.tapToTurn !== false) {
            if (x < third) { this.prev(); return }
            if (x > width - third) { this.next(); return }
        }
        post('tap', { zone: 'center' })
    }

    #onOuterClick(e) {
        if (e.defaultPrevented) return
        if (this.lastSelection) { this.clearSelection(); return }
        const width = window.innerWidth
        const third = width / 3
        if (this.settings?.flow !== 'scrolled' && this.settings?.tapToTurn !== false) {
            if (e.clientX < third) { this.prev(); return }
            if (e.clientX > width - third) { this.next(); return }
        }
        post('tap', { zone: 'center' })
    }

    #onSelectionChange(doc, index) {
        if (this.ttsActive) return
        clearTimeout(this.selectionTimer)
        this.selectionTimer = setTimeout(() => {
            const sel = doc.getSelection()
            if (!sel || sel.isCollapsed || !sel.rangeCount) {
                if (this.lastSelection) post('selectionCleared', {})
                this.lastSelection = null
                return
            }
            const range = sel.getRangeAt(0)
            const text = range.toString().trim()
            if (!text) return
            const cfi = this.view.getCFI(index, range)
            this.lastSelection = { cfi, text, index }
            post('selection', { cfi, text })
        }, 250)
    }

    getSelection() {
        return JSON.stringify(this.lastSelection ?? null)
    }

    clearSelection() {
        for (const doc of this.docs.keys()) {
            try { doc.getSelection()?.removeAllRanges() } catch (_) { /* detached */ }
        }
        this.lastSelection = null
    }

    #onRelocate(detail) {
        this.relocated = true
        const { fraction, cfi, tocItem, pageItem, section, time, location } = detail
        const index = section?.current ?? 0
        const sec = this.view.book.sections[index]
        post('relocate', {
            cfi: cfi ?? '',
            fraction: fraction ?? 0,
            href: tocItem?.href ?? sec?.id ?? '',
            tocLabel: (tocItem?.label ?? '').trim(),
            sectionIndex: index,
            sectionTotal: section?.total ?? 0,
            remainingSectionBytes: Math.max(0, (time?.section ?? 0) * 1600),
            remainingTotalBytes: Math.max(0, (time?.total ?? 0) * 1600),
            locationCurrent: location?.current ?? 0,
            locationTotal: location?.total ?? 0,
            pageLabel: pageItem?.label ?? '',
            ...this.#pageInfo(index),
        })
    }

    // Page numbers for the header/footer. Chapter pages come straight from the paginator (page 0 and the last
    // page are padding). Book pages are an estimate: bytes before this chapter / average bytes per page of the
    // chapters measured so far (chapters of < 3 pages are too small to say much); reset when the layout changes.
    #sizesReady() {
        if (this.sectionSizes) return
        const sections = this.view.book.sections
        this.sectionSizes = sections.map(s => s.linear !== 'no' && s.size > 0 ? s.size : 0)
        this.sectionBefore = []
        let sum = 0
        for (const s of this.sectionSizes) { this.sectionBefore.push(sum); sum += s }
        this.measured = new Map()
    }

    #pageInfo(index) {
        const out = { pageInSection: 0, pagesInSection: 0, bookPage: 0, bookPages: 0 }
        try {
            const r = this.view.renderer
            if (this.view.isFixedLayout) {
                const total = this.view.book.sections.length
                return { pageInSection: 1, pagesInSection: 1, bookPage: index + 1, bookPages: total }
            }
            let pages, page
            if (r.scrolled) {
                pages = Math.max(1, Math.round(r.viewSize / r.size))
                page = Math.min(pages, r.page + 1)
            } else {
                pages = Math.max(1, r.pages - 2)
                page = Math.min(pages, Math.max(1, r.page))
            }
            out.pageInSection = page
            out.pagesInSection = pages
            this.#sizesReady()
            const known = this.measured.get(index)
            if (known !== undefined && known !== pages) this.measured.clear()
            this.measured.set(index, pages)
            let bytes = 0, measuredPages = 0
            for (const [i, p] of this.measured) {
                if (p < 3) continue
                bytes += this.sectionSizes[i] || 0
                measuredPages += p
            }
            if (bytes > 0 && measuredPages > 0) {
                const bytesPerPage = bytes / measuredPages
                out.bookPage = Math.max(1, Math.round((this.sectionBefore[index] || 0) / bytesPerPage) + page)
                out.bookPages = Math.max(out.bookPage, Math.round(this.totalBytes / bytesPerPage))
            }
        } catch (_) { /* leave zeros: the app hides items it has no data for */ }
        return out
    }

    // Annotations
    #styleFn(style) {
        switch (style) {
            case 'underline': return Overlayer.underline
            case 'strikethrough': return Overlayer.strikethrough
            case 'squiggly': return Overlayer.squiggly
            default: return Overlayer.highlight
        }
    }

    async #indexOf(cfi) {
        if (this.annotationIndex.has(cfi)) return this.annotationIndex.get(cfi)
        try {
            const resolved = await this.view.resolveNavigation(cfi)
            const index = resolved?.index
            this.annotationIndex.set(cfi, index)
            return index
        } catch (_) {
            return -1
        }
    }

    async #onCreateOverlay({ index }) {
        for (const a of this.annotations.values()) {
            if (await this.#indexOf(a.value) === index) {
                this.view.addAnnotation(a).catch(() => {})
            }
        }
    }

    #onDrawAnnotation({ draw, annotation }) {
        const style = annotation.style ?? 'highlight'
        const color = annotation.color ?? '#FFD54F'
        draw(this.#styleFn(style), style === 'highlight' ? { color } : { color, width: 2 })
    }

    setAnnotations(list) {
        for (const a of this.annotations.values()) this.view?.deleteAnnotation(a).catch(() => {})
        this.annotations.clear()
        for (const a of list ?? []) {
            this.annotations.set(a.value, a)
            this.view?.addAnnotation(a).catch(() => {})
        }
    }

    addAnnotation(a) {
        this.annotations.set(a.value, a)
        this.view?.addAnnotation(a).catch(() => {})
    }

    removeAnnotation(cfi) {
        const a = this.annotations.get(cfi) ?? { value: cfi }
        this.annotations.delete(cfi)
        this.view?.deleteAnnotation(a).catch(() => {})
    }

    // Navigation
    next() { return this.view?.next() }
    prev() { return this.view?.prev() }
    goTo(target) { return this.view?.goTo(target).catch(e => post('error', { message: e.message })) }
    goToFraction(f) { return this.view?.goToFraction(f) }
    // Seek inside the current chapter. f = slider position, 0 = first page, 1 = last page. In paginated mode
    // foliate maps a numeric anchor to page round(anchor * (pages - 1)) + 1, so f goes through unchanged;
    // when scrolling, an anchor is a fraction of the whole chapter, so convert from "start of page n".
    goToChapterFraction(f) {
        const r = this.view?.renderer
        const index = r?.getContents?.()[0]?.index
        if (index == null) return
        let anchor = Math.max(0, Math.min(1, f))
        if (r.scrolled) {
            const pages = Math.max(1, Math.round(r.viewSize / r.size))
            anchor = Math.round(anchor * (pages - 1)) / pages
        }
        return r.goTo({ index, anchor })
    }
    nextSection() { return this.view?.renderer?.nextSection() }
    prevSection() { return this.view?.renderer?.prevSection() }

    // Search
    async search(query) {
        try {
            for await (const result of this.view.search({ query })) {
                if (result === 'done') { post('searchDone', {}); break }
                if (result.subitems) {
                    post('searchResults', {
                        label: result.label ?? '',
                        items: result.subitems.map(i => ({
                            cfi: i.cfi,
                            pre: i.excerpt?.pre ?? '',
                            match: i.excerpt?.match ?? '',
                            post: i.excerpt?.post ?? '',
                        })),
                    })
                } else if (result.progress != null) {
                    post('searchProgress', { progress: result.progress })
                }
            }
        } catch (e) {
            post('error', { message: e?.message ?? String(e) })
            post('searchDone', {})
        }
    }

    clearSearch() { this.view?.clearSearch() }

    // Statistics: count words per linear section so the app can show words-per-minute.
    async computeWordStats() {
        try {
            const sections = this.view.book.sections
            let words = 0
            let bytes = 0
            const perSection = []
            for (const s of sections) {
                if (s.linear === 'no' || !s.createDocument) { perSection.push(0); continue }
                const doc = await s.createDocument()
                const text = doc?.body?.textContent ?? ''
                const n = text.split(/\s+/).filter(Boolean).length
                perSection.push(n)
                words += n
                bytes += s.size > 0 ? s.size : 0
                await new Promise(r => setTimeout(r, 0))
            }
            post('wordStats', { words, bytes, perSection })
        } catch (e) {
            console.error(e)
        }
    }

    // Text-to-speech: the app speaks plain-text segments and reports marks back.
    #ssmlToSegments(ssml) {
        if (!ssml) return []
        const doc = new DOMParser().parseFromString(ssml, 'application/xml')
        const segments = []
        let current = { mark: null, text: '' }
        const walk = node => {
            for (const child of node.childNodes) {
                if (child.nodeType === Node.TEXT_NODE) {
                    current.text += child.nodeValue
                } else if (child.nodeType === Node.ELEMENT_NODE) {
                    if (child.localName === 'mark') {
                        if (current.text.trim()) segments.push(current)
                        current = { mark: child.getAttribute('name'), text: '' }
                    } else if (child.localName === 'break') {
                        current.text += ' '
                    } else {
                        walk(child)
                    }
                }
            }
        }
        walk(doc.documentElement)
        if (current.text.trim()) segments.push(current)
        return segments.map(s => ({ mark: s.mark ?? '', text: s.text.replace(/\s+/g, ' ').trim() }))
    }

    async #sendBlock(ssml) {
        // Walk forward until we find a block with speakable text or reach the end of the book.
        let guard = 0
        while (guard++ < 500) {
            if (ssml === undefined || ssml === null) {
                const r = this.view.renderer
                const contents = r.getContents()[0]
                const lastIndex = this.view.book.sections.length - 1
                if (!contents || contents.index >= lastIndex) {
                    this.ttsActive = false
                    post('ttsEnd', {})
                    return
                }
                await r.nextSection()
                await this.view.initTTS(this.ttsGranularity)
                ssml = this.view.tts.start()
                continue
            }
            const segments = this.#ssmlToSegments(ssml)
            if (segments.length) {
                post('ttsBlock', { segments, lang: this.view.language?.canonical ?? '' })
                return
            }
            ssml = this.view.tts.next()
        }
        this.ttsActive = false
        post('ttsEnd', {})
    }

    async ttsStart(fromSelection) {
        try {
            this.ttsActive = true
            await this.view.initTTS(this.ttsGranularity)
            let ssml
            const sel = this.lastSelection
            if (fromSelection && sel) {
                const resolved = await this.view.resolveNavigation(sel.cfi)
                const doc = [...this.docs.entries()].find(([, i]) => i === resolved.index)?.[0]
                const range = doc ? resolved.anchor(doc) : null
                ssml = range ? this.view.tts.from(range) : this.view.tts.start()
            } else {
                // Start at the current visible position when possible.
                const range = this.view.lastLocation?.range
                ssml = range ? this.view.tts.from(range) : this.view.tts.start()
            }
            this.clearSelection()
            await this.#sendBlock(ssml)
        } catch (e) {
            this.ttsActive = false
            post('error', { message: `TTS: ${e?.message ?? e}` })
            post('ttsEnd', {})
        }
    }

    ttsMark(mark) {
        try { if (mark) this.view.tts?.setMark(mark) } catch (_) { /* ignore */ }
    }

    async ttsNext() {
        try {
            this.ttsActive = true
            await this.#sendBlock(this.view.tts?.next())
        } catch (e) {
            post('error', { message: `TTS: ${e?.message ?? e}` })
        }
    }

    async ttsPrev() {
        try {
            this.ttsActive = true
            await this.#sendBlock(this.view.tts?.prev() ?? this.view.tts?.start())
        } catch (e) {
            post('error', { message: `TTS: ${e?.message ?? e}` })
        }
    }

    async ttsResume() {
        try {
            this.ttsActive = true
            await this.view.initTTS(this.ttsGranularity)
            await this.#sendBlock(this.view.tts?.resume())
        } catch (e) {
            post('error', { message: `TTS: ${e?.message ?? e}` })
        }
    }

    // Speed reading (RSVP): the app shows the words; this side lists them and keeps the page in sync.
    #speedCollect(startRange) {
        const contents = this.view.renderer.getContents()[0]
        if (!contents) return null
        const { doc, index } = contents
        const BLOCK = 'p,div,li,h1,h2,h3,h4,h5,h6,blockquote,td,th,dd,dt,pre,section,article,figcaption,tr'
        const visible = new Map()
        const isVisible = el => {
            if (!visible.has(el)) {
                let ok = true
                try { ok = el.checkVisibility ? el.checkVisibility() : true } catch (_) { ok = true }
                visible.set(el, ok)
            }
            return visible.get(el)
        }
        const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, {
            acceptNode: n => {
                const el = n.parentElement
                if (!el || el.closest('script,style,noscript,svg,rt,rp')) return NodeFilter.FILTER_REJECT
                return isVisible(el) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
            },
        })
        const tokens = []
        let lastBlock = null
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            const block = node.parentElement.closest(BLOCK) ?? doc.body
            const re = /\S+/g
            let m
            while ((m = re.exec(node.data))) {
                const text = m[0].replace(/[\u00ad\u200b-\u200d\ufeff]/g, '')
                if (!/[\p{L}\p{N}]/u.test(text)) continue
                if (startRange) {
                    let after = true
                    try { after = startRange.comparePoint(node, m.index) >= 0 } catch (_) { after = true }
                    if (!after) continue
                }
                if (tokens.length && lastBlock !== block) tokens[tokens.length - 1].para = true
                lastBlock = block
                tokens.push({ node, start: m.index, end: m.index + m[0].length, text, para: false })
            }
        }
        if (tokens.length) tokens[tokens.length - 1].para = true
        return { tokens, index, doc }
    }

    async #speedLoad(startRange) {
        let range = startRange
        for (let guard = 0; guard < 300; guard++) {
            const found = this.#speedCollect(range)
            if (found?.tokens.length) {
                this.speed = found
                post('speedBlock', {
                    words: found.tokens.map(t => t.text + (t.para ? '\n' : '')),
                    section: found.index,
                    lang: this.view.language?.canonical ?? '',
                })
                return
            }
            const r = this.view.renderer
            const contents = r.getContents()[0]
            if (!contents || contents.index >= this.view.book.sections.length - 1) break
            await r.nextSection()
            range = null
        }
        this.speed = null
        post('speedEnd', {})
    }

    async speedStart() {
        try {
            if (this.view.isFixedLayout) { post('speedUnsupported', {}); return }
            this.clearSelection()
            await this.#speedLoad(this.view.lastLocation?.range ?? null)
        } catch (e) {
            post('error', { message: `Speed reading: ${e?.message ?? e}` })
            post('speedEnd', {})
        }
    }

    async speedNext() {
        try {
            const r = this.view.renderer
            const contents = r.getContents()[0]
            if (!contents || contents.index >= this.view.book.sections.length - 1) {
                this.speed = null
                post('speedEnd', {})
                return
            }
            await r.nextSection()
            await this.#speedLoad(null)
        } catch (e) {
            post('error', { message: `Speed reading: ${e?.message ?? e}` })
            post('speedEnd', {})
        }
    }

    // Moves the hidden page to the word being shown, so progress, bookmarks and sync follow along.
    speedSync(i) {
        const s = this.speed
        const t = s?.tokens[Math.min(Math.max(0, i), (s?.tokens.length ?? 1) - 1)]
        if (!t) return
        try {
            const range = s.doc.createRange()
            range.setStart(t.node, t.start)
            range.setEnd(t.node, t.end)
            const cfi = this.view.getCFI(s.index, range)
            this.view.goTo(cfi).catch(() => {})
        } catch (_) { /* the page was unloaded; the next block resyncs */ }
    }

    speedStop(i) {
        if (i >= 0) this.speedSync(i)
        this.speed = null
    }

    ttsStop() {
        this.ttsActive = false
        this.clearSelection()
    }
}

const reader = new Reader()
globalThis.reader = reader
post('bridgeReady', {})
