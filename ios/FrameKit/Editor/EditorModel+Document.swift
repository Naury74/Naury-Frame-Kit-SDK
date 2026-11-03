import CoreGraphics
import Foundation

extension EditorModel {
    /// 지금 보여 줄 도구. 문서 보정은 문서를 찾았거나, 이미 폈거나, PDF로 저장할 때만 나온다.
    var visibleTools: [ImageTool] {
        guard pages.indices.contains(pageIndex) else { return tools }
        let page = pages[pageIndex]
        let showDocument = page.detection != nil || page.original != nil || request.export.format == .pdf
        var result = tools.filter { ($0 != .document || showDocument) && ($0 != .cutout || cutoutAvailable) }
        // PDF로 저장하면 문서 보정을 맨 앞에 둔다.
        if request.export.format == .pdf, let index = result.firstIndex(of: .document) {
            result.insert(result.remove(at: index), at: 0)
        }
        return result
    }

    /// 문서를 찾았고 아직 펴지 않았으면 안내를 보인다.
    var showsDocumentSuggestion: Bool {
        guard pages.indices.contains(pageIndex), activeTool == nil, tools.contains(.document), !documentSuggestionDismissed else { return false }
        let page = pages[pageIndex]
        return page.detection != nil && page.original == nil
    }

    /// 문서 도구에서 보여 줄 원본(편 적이 있으면 펴기 전 원본).
    var documentSource: UprightImage? {
        guard pages.indices.contains(pageIndex) else { return nil }
        return pages[pageIndex].original?.preview ?? pages[pageIndex].preview
    }

    func detectDocument() {
        guard tools.contains(.document), pages.indices.contains(pageIndex), !pages[pageIndex].detected else { return }
        let index = pageIndex
        let image = documentSource
        pages[index].detected = true
        Task.detached(priority: .utility) {
            let detection = image.flatMap { DocumentEngine.detect($0.cgImage) }
            await MainActor.run {
                guard self.pages.indices.contains(index) else { return }
                self.pages[index].detection = detection
            }
        }
    }

    func moveDocumentCorner(_ corner: Int, to point: NPoint) {
        guard var quad = documentQuad, quad.corners.indices.contains(corner) else { return }
        quad.corners[corner] = NPoint(x: min(1, max(0, point.x)), y: min(1, max(0, point.y)))
        documentQuad = quad
    }

    func redetectDocument() {
        guard let image = documentSource else { return }
        documentBusy = true
        let index = pageIndex
        Task.detached(priority: .userInitiated) {
            let detection = DocumentEngine.detect(image.cgImage)
            await MainActor.run {
                self.documentBusy = false
                guard self.pages.indices.contains(index) else { return }
                if let detection {
                    self.pages[index].detection = detection
                    self.documentQuad = detection.quad
                } else {
                    self.showNotice("document_failed")
                }
            }
        }
    }

    func wholeDocument() { documentQuad = .whole }

    /// 지금 장을 펴서 새 원본으로 쓴다.
    func applyDocument() {
        guard let quad = documentQuad, pages.indices.contains(pageIndex), !documentBusy else { return }
        guard quad.isUsable else { showNotice("document_failed"); return }
        let index = pageIndex
        let page = pages[index]
        let original = page.original ?? (page.source, page.preview)
        let detection = page.detection
        let style = scanStyle
        documentBusy = true
        Task.detached(priority: .userInitiated) {
            do {
                let url = try DocumentEngine.rectify(source: original.source, quad: quad, detection: detection, style: style)
                let source = try SourceImage(url: url, removeOnClose: true)
                let preview = try source.decode(maxLongEdge: 2048)
                await MainActor.run {
                    self.documentBusy = false
                    self.replacePage(index, source: source, preview: preview, original: original, quad: quad)
                    self.closeToolAfterDocument()
                    self.showNotice("document_applied")
                    self.recognizeDocumentText(preview)
                }
            } catch {
                await MainActor.run {
                    self.documentBusy = false
                    self.showNotice("document_failed")
                }
            }
        }
    }

    /// 모든 장을 찾아서 편다. 문서를 찾지 못한 장은 건너뛴다.
    func scanAllPages() {
        guard !documentBusy else { return }
        let style = scanStyle
        let items = pages.enumerated().map { ($0.offset, $0.element.original ?? ($0.element.source, $0.element.preview), $0.element.detection) }
        documentBusy = true
        Task.detached(priority: .userInitiated) {
            var skipped = 0
            for (index, original, known) in items {
                guard let detection = known ?? DocumentEngine.detect(original.preview.cgImage) else { skipped += 1; continue }
                do {
                    let url = try DocumentEngine.rectify(source: original.source, quad: detection.quad, detection: detection, style: style)
                    let source = try SourceImage(url: url, removeOnClose: true)
                    let preview = try source.decode(maxLongEdge: 2048)
                    await MainActor.run {
                        self.pages[index].detection = detection
                        self.replacePage(index, source: source, preview: preview, original: original, quad: detection.quad)
                    }
                } catch {
                    skipped += 1
                }
            }
            let missed = skipped
            await MainActor.run {
                self.documentBusy = false
                self.closeToolAfterDocument()
                self.showNotice(missed > 0 ? "document_batch_skipped" : "document_applied")
            }
        }
    }

    /// 편 문서에서 글자를 찾아 '글자 보기' 안내를 띄운다.
    func recognizeDocumentText(_ preview: UprightImage) {
        documentText = nil
        Task.detached(priority: .utility) {
            let lines = TextRecognizer.recognize(preview.cgImage) ?? []
            let text = lines.map(\.text).joined(separator: "\n")
            await MainActor.run { self.documentText = text.isEmpty ? nil : text }
        }
    }

    /// 펴기 전 원본으로 되돌린다.
    func restoreDocument() {
        guard pages.indices.contains(pageIndex), let original = pages[pageIndex].original else { return }
        pages[pageIndex].source.close()
        pages[pageIndex].source = original.source
        pages[pageIndex].preview = original.preview
        pages[pageIndex].original = nil
        pages[pageIndex].appliedQuad = nil
        resetPageHistory(pageIndex)
        closeToolAfterDocument()
    }

    private func replacePage(_ index: Int, source: SourceImage, preview: UprightImage, original: (source: SourceImage, preview: UprightImage),
                             quad: DocQuad) {
        guard pages.indices.contains(index) else { source.close(); return }
        // 앞서 편 결과는 원본이 아니므로 닫는다.
        if pages[index].original != nil { pages[index].source.close() }
        pages[index].original = original
        pages[index].source = source
        pages[index].preview = preview
        pages[index].appliedQuad = quad
        pages[index].thumbnail = preview.thumbnail(maxSide: 160)
        resetPageHistory(index)
    }

    /// 이미지가 바뀌면 기하·요소 좌표가 맞지 않으므로 기록을 새로 시작한다. 워터마크는 유지한다.
    private func resetPageHistory(_ index: Int) {
        var edit = PhotoEdit()
        edit.watermark = pages[index].history.current.watermark
        pages[index].history = EditHistory(edit)
        if index == pageIndex {
            rendered = nil
            requestRender()
        }
    }
}

extension EditorModel {

    var hasCutout: Bool { displayed.cutout != nil }

    func removeBackground() {
        guard let preview, !cutoutBusy else { return }
        cutoutBusy = true
        cutoutError = nil
        Task.detached(priority: .userInitiated) {
            do {
                let mask = try SubjectCutout.mask(for: preview.cgImage)
                await MainActor.run {
                    self.cutoutBusy = false
                    var edit = self.history.current
                    edit.cutout = mask
                    self.history.commit(edit)
                    self.requestRender()
                }
            } catch {
                await MainActor.run {
                    self.cutoutBusy = false
                    self.cutoutError = (error as? EditorError) ?? EditorError(code: .unknown)
                }
            }
        }
    }

    func restoreBackground() {
        var edit = history.current
        edit.cutout = nil
        history.commit(edit)
        requestRender()
    }
}
