# Rules for shrinking the release build (R8).

# Keep line numbers in crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PdfBox's optional JPEG 2000 support (a separate library Hok6 doesn't include), as its README advises.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
