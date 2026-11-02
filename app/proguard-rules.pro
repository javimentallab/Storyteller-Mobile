# PdfBox: only PDFs locked to a certificate need Bouncy Castle (left out of the app), and only
# JPEG 2000 images need the other one, which the text reader never decodes.
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
