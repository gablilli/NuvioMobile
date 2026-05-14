import UIKit
import GoogleCast

private let castChooserRequestNotification = Notification.Name("NuvioCastChooserRequest")

final class CastBridgeCoordinator {
    static let shared = CastBridgeCoordinator()

    private var observer: NSObjectProtocol?
    private init() {}

    func start() {
        let criteria = GCKDiscoveryCriteria(
            applicationID: kGCKDefaultMediaReceiverApplicationID
        )
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = true

        observer = NotificationCenter.default.addObserver(
            forName: castChooserRequestNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.presentCastChooser()
        }
    }

    private func presentCastChooser() {
        GCKCastContext.sharedInstance().presentCastDialog()
    }
}
