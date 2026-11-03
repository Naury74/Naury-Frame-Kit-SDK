import Foundation
import SwiftUI

final class FrameKitBundle {
    static let bundle = Bundle(for: FrameKitBundle.self)
}

/// SDK 문자열. 키는 Android 문자열 이름에서 `framekit_`를 뺀 것이다.
struct Strings: Sendable {
    private let bundle: Bundle

    /// - Parameter localeIdentifier: 강제할 언어. `nil`이면 기기 언어. 지원하지 않으면 영어.
    init(localeIdentifier: String?) {
        let base = FrameKitBundle.bundle
        let preferences = localeIdentifier.map { [$0] } ?? Locale.preferredLanguages
        let available = base.localizations.filter { $0 != "Base" }
        let match = Bundle.preferredLocalizations(from: available, forPreferences: preferences).first ?? "en"
        if let path = base.path(forResource: match, ofType: "lproj"), let localized = Bundle(path: path) {
            bundle = localized
        } else if let path = base.path(forResource: "en", ofType: "lproj"), let english = Bundle(path: path) {
            bundle = english
        } else {
            bundle = base
        }
    }

    func callAsFunction(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: "Localizable")
    }

    func callAsFunction(_ key: String, _ args: CVarArg...) -> String {
        String(format: self(key), locale: Locale(identifier: bundle.preferredLocalizations.first ?? "en"), arguments: args)
    }
}

/// SDK 아이콘(Android 벡터 아이콘에서 만든 것).
enum Icons {
    static func image(_ name: String) -> Image {
        Image("fk_\(name)", bundle: FrameKitBundle.bundle).renderingMode(.template)
    }
}
