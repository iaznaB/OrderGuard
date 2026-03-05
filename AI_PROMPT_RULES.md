## Mandatory Logic (DO NOT MODIFY)

### Loop Prevention
* **Ongoing Filter:** Always keep `sbn.isOngoing` check in `OrderNotificationListener.kt`. Removing this allows the app to process its own foreground service notifications, creating an infinite feedback loop.
* **Self-Package Exclusion:** Ensure the logic explicitly ignores notifications where `sbn.packageName == packageName` (the app's own package).
* **Keyword Debouncing:** Maintain keyword filters that prevent "DETECTED" logs from re-triggering logic if a notification is updated rather than replaced.

### Trigger Reliability
* **Package Whitelist:** The `TYPE_NOTIFICATION_STATE_CHANGED` block and the `onNotificationPosted` listener must always include these core packages:
    * `com.doordash.driverapp` (DoorDash)
    * `com.ubercab.driver` (Uber Eats)
    * `com.grubhub.driver` (GrubHub)
* **Intent Filters:** Do not modify the `android.service.notification.NotificationListenerService` action in the Manifest or the code that checks for it, as this is how the OS binds to the service.
* **Main Looper Execution:** Always use `Handler(Looper.getMainLooper()).postDelayed` for multi-step sequences (e.g., detecting an order then performing a click) to ensure UI stability and prevent "CalledFromWrongThread" exceptions.

### App-Specific Selectors
* **DoorDash IDs:** Never "optimize" or rename View IDs used for automation:
    * `decline_button`: Used to trigger the decline flow.
    * `offer_price` / `offer_mileage`: Used for parsing order value.
* **Keyword Matching:** Maintain specific string literals for order detection (e.g., "New Order", "Delivery Opportunity", "Accept?").
* **History Management:** Keep the logic that removes "DETECTED" logs when a "DECLINED" action occurs to prevent duplicate entries in the user's history feed.

### Authentication Security (DO NOT MODIFY WITHOUT APPROVAL)
* **Google Client IDs:** Do not modify or replace the `serverClientId` string in `AuthManager.kt` or any other authentication-related file without explicit user approval.
* **Auth State Management:** Maintain the `USER_EMAIL` preference key and the `MutableStateFlow` logic in `AuthManager.kt` to ensure reliable login state persistence.
* **Login Flow:** Never bypass the `LoginScreen` or modify the conditional logic in `MainActivity.kt` that redirects unauthenticated users to the login screen.
* **Dependencies:** Do not remove or downgrade authentication-related dependencies (Credentials, Play Services Auth, GoogleID) in `build.gradle.kts`.
