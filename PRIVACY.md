# Privacy policy — Storyteller Mobile

*Last updated: 2 November 2026*

Storyteller Mobile is published by javimentallab. The app has no accounts, no ads, no analytics and no crash
reporting, and it has no server of its own.

## What leaves the phone

Only what a job needs, and only to the service you set up for that job in *Models*, usually an AI service:

- **Narration:** the text of the book, sentence by sentence. If you upload a voice, that recording too.
- **Transcription:** the audio of an imported audiobook, in pieces of two minutes. If you turn on the check of
  narrated sentences, their audio too.
- **Translation:** the text of the document.

Each request carries the access key you gave for that service. Whoever runs a service can see what you send
it, under its own terms, so choose services you trust. Before narrating, the app tells you how much text it
will send and to whom. Before translating, it tells you to whom.

If an address is outside your home network and doesn't use HTTPS, the app warns you that your text would travel
without encryption, and it doesn't send your key there.

## Reporting content

*Report content* opens a Google Form inside the app. What you type in it reaches the developer through Google,
under Google's terms. The app adds nothing to it: not your books, your keys or anything else.

## What is kept, and for how long

The developer receives nothing from the app except what you write in that form, and keeps those reports only as
long as it takes to deal with them. What a service you chose keeps, and for how long, depends on its own terms.

On the phone, your books, their audio and subtitles, translations, settings and pronunciation dictionaries stay in
the app's own folders until you delete them or the app. Access keys are encrypted with the Android Keystore.
Deleting the app deletes all of this, except files you saved elsewhere with *Save as*.

## Backups

Android's cloud backup is disabled for this app. On some phones, moving to a new phone can still copy the app's
data directly from one device to the other. Access keys don't survive that, because the key that encrypts them
never leaves the old phone: you would type them again.

## Permissions

- **Internet and network state:** to reach the services you chose.
- **Notifications:** to show the progress of a job and when it ends.
- **Foreground services and wake lock:** to keep narrating, transcribing, translating or playing with the
  screen off.

## Contact

Questions about this policy: open an issue at
https://github.com/javimentallab/Storyteller-Mobile/issues
