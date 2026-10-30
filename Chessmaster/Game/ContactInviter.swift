// Chessmaster — GPL-3.0-or-later
import ContactsUI
import MessageUI
import UIKit

/// "Invite from Contacts": the system contact picker (runs out of process,
/// so no Contacts permission prompt and we only ever see the one number the
/// user taps), then an iMessage/SMS composer addressed to that number.
@MainActor
final class ContactInviter: NSObject {
    static let shared = ContactInviter()

    private var onPick: ((_ name: String, _ phone: String) -> Void)?
    private var onSent: ((Bool) -> Void)?

    func pickPhone(_ completion: @escaping (_ name: String, _ phone: String) -> Void) {
        let picker = CNContactPickerViewController()
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
        // One number: pick the contact; several: the user chooses which.
        picker.predicateForSelectionOfContact = NSPredicate(format: "phoneNumbers.@count == 1")
        picker.delegate = self
        onPick = completion
        Self.topViewController()?.present(picker, animated: true)
    }

    /// Opens Messages addressed to `phone` with `body`; falls back to an
    /// sms: link where the in-app composer isn't available (e.g. simulator).
    func sendText(to phone: String, body: String, completion: ((Bool) -> Void)? = nil) {
        guard MFMessageComposeViewController.canSendText() else {
            var parts = URLComponents()
            parts.scheme = "sms"
            parts.path = phone
            parts.queryItems = [URLQueryItem(name: "body", value: body)]
            if let url = parts.url { UIApplication.shared.open(url) }
            completion?(true)
            return
        }
        let composer = MFMessageComposeViewController()
        composer.recipients = [phone]
        composer.body = body
        composer.messageComposeDelegate = self
        onSent = completion
        Self.topViewController()?.present(composer, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.rootViewController
        var top = root
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    private func picked(name: String, phone: String) {
        let callback = onPick
        onPick = nil
        // The picker dismisses itself; present the composer once it's gone.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600))
            callback?(name, phone)
        }
    }

    /// Name + number as plain strings, read on the delegate's thread.
    nonisolated private static func details(_ contact: CNContact, _ number: CNPhoneNumber?) -> (String, String)? {
        guard let phone = (number ?? contact.phoneNumbers.first?.value)?.stringValue else { return nil }
        return (CNContactFormatter.string(from: contact, style: .fullName) ?? phone, phone)
    }
}

extension ContactInviter: CNContactPickerDelegate {
    nonisolated func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        guard let (name, phone) = Self.details(contact, nil) else { return }
        MainActor.assumeIsolated { picked(name: name, phone: phone) }
    }

    nonisolated func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
        guard let (name, phone) = Self.details(contactProperty.contact, contactProperty.value as? CNPhoneNumber)
        else { return }
        MainActor.assumeIsolated { picked(name: name, phone: phone) }
    }

    nonisolated func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        MainActor.assumeIsolated { onPick = nil }
    }
}

extension ContactInviter: MFMessageComposeViewControllerDelegate {
    nonisolated func messageComposeViewController(_ controller: MFMessageComposeViewController,
                                                  didFinishWith result: MessageComposeResult) {
        MainActor.assumeIsolated {
            controller.dismiss(animated: true)
            onSent?(result == .sent)
            onSent = nil
        }
    }
}
