# Privacy policy — Storyteller Mobile

*Last updated: 7 November 2026*

## 1. Publisher and scope

The Storyteller Mobile application (the "App") is published by javimentallab. The App has no user accounts, advertising, analytics tools, crash reporting systems or server of its own.

## 2. Data transmitted outside the device

The App transmits only the data required for each task, and exclusively to the service that the User has set up for that task in the *Models* section, usually an artificial intelligence service:

- **Narration:** the text of the book, sentence by sentence, and, where applicable, the voice recording uploaded by the User.
- **Transcription:** the audio of the imported audiobook, in two-minute segments, and, if the User enables the check of narrated sentences, the audio of those sentences as well.
- **Translation:** the text of the document.

Each request includes the access key provided by the User for the relevant service. The provider of each service can access the data sent to it, under its own terms, so the User should choose services the User trusts. Before narrating, the App informs the User of the amount of text to be sent and of its recipient; before translating, it informs the User of the recipient.

If the address of a service is outside the User's home network and does not use HTTPS, the App warns that the text would be transmitted unencrypted and does not send the access key to that address.

## 3. Reporting content

The *Report content* function opens a Google form within the App. The information the User enters in it reaches the developer through Google, under Google's terms. The App adds no other data: neither the books, nor the access keys, nor any other information.

## 4. Data retention

The developer receives no data from the App other than what the User writes in that form, and keeps those reports only for as long as necessary to handle them. Retention of data by the services chosen by the User is governed by their own terms.

On the device, the books, their audio and subtitles, translations, settings and pronunciation dictionaries remain in the App's own folders until the User deletes them or uninstalls the App. Access keys are stored encrypted with the Android Keystore. Uninstalling the App deletes all of this data, except for files the User has saved elsewhere with the *Save as* function.

## 5. Backups

Android cloud backup is disabled for this App. On some devices, when moving to a new device, the App's data may be copied directly from one device to the other. Access keys are not preserved in that process, since the key that encrypts them never leaves the original device, so the User will have to enter them again.

## 6. Permissions

The App uses the permissions listed below. Android only asks the User for the notifications permission, from Android 13 onwards; the others are granted when the App is installed.

- **Internet and network state:** to reach the services chosen by the User.
- **Notifications:** to report the progress of each task and its completion.
- **Foreground services and wake lock:** to keep narrating, transcribing, translating or playing with the screen off.

## 7. Contact

Enquiries about this policy may be addressed to the developer by opening an issue at https://github.com/javimentallab/Storyteller-Mobile/issues.
