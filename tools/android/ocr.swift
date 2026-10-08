import Foundation
import ImageIO
import Vision

struct TextResult: Encodable {
    let text: String
    let confidence: Float
    let bounds: [Int]
}

func recognize(_ data: Data, request: VNRecognizeTextRequest) throws -> Data {
    guard data.count <= 40 * 1024 * 1024,
          let source = CGImageSourceCreateWithData(data as CFData, nil),
          let type = CGImageSourceGetType(source) as String?,
          type == "public.png" || type == "public.jpeg",
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        throw NSError(domain: "TicketsOCR", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "输入须为有效的 PNG 或 JPEG 图片，且不超过 40 MiB。"])
    }

    try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])

    let width = Double(image.width)
    let height = Double(image.height)
    let results = (request.results ?? []).compactMap { observation -> TextResult? in
        guard let candidate = observation.topCandidates(1).first,
              !candidate.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        let box = observation.boundingBox
        let bounds = [
            max(0, min(image.width, Int(floor(box.minX * width)))),
            max(0, min(image.height, Int(floor((1 - box.maxY) * height)))),
            max(0, min(image.width, Int(ceil(box.maxX * width)))),
            max(0, min(image.height, Int(ceil((1 - box.minY) * height))))
        ]
        return TextResult(text: candidate.string, confidence: candidate.confidence, bounds: bounds)
    }.sorted {
        if $0.bounds[1] != $1.bounds[1] { return $0.bounds[1] < $1.bounds[1] }
        return $0.bounds[0] < $1.bounds[0]
    }
    return try JSONEncoder().encode(results)
}

func readExactly(_ count: Int) throws -> Data {
    var data = Data()
    while data.count < count {
        let chunk = FileHandle.standardInput.readData(ofLength: count - data.count)
        if chunk.isEmpty { break }
        data.append(chunk)
    }
    return data
}

func serve(_ request: VNRecognizeTextRequest) throws {
    while true {
        let head = try readExactly(4)
        if head.isEmpty { return }
        guard head.count == 4 else { throw NSError(domain: "TicketsOCR", code: 2) }
        let length = head.reduce(0) { ($0 << 8) | Int($1) }
        guard length <= 40 * 1024 * 1024 else { throw NSError(domain: "TicketsOCR", code: 3) }
        let data = try readExactly(length)
        guard data.count == length else { throw NSError(domain: "TicketsOCR", code: 4) }
        let output: Data = autoreleasepool {
            if length == 0 { return Data("{\"ready\":true}".utf8) }
            do { return try recognize(data, request: request) }
            catch { return Data("{\"error\":\"recognition_failed\"}".utf8) }
        }
        var count = UInt32(output.count).bigEndian
        FileHandle.standardOutput.write(Data(bytes: &count, count: 4))
        FileHandle.standardOutput.write(output)
    }
}

do {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = false
    request.minimumTextHeight = 0.005
    if CommandLine.arguments.contains("--server") {
        try serve(request)
    } else {
        let result = try autoreleasepool { try recognize(FileHandle.standardInput.readDataToEndOfFile(), request: request) }
        FileHandle.standardOutput.write(result)
        FileHandle.standardOutput.write(Data([10]))
    }
} catch {
    let message = "本地 OCR 失败：\(error.localizedDescription)\n"
    FileHandle.standardError.write(Data(message.utf8))
    exit(1)
}
