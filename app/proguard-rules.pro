# R8 rules for the release build (Technical Design 14). The libraries (Room, Media3, WorkManager, Glance, Coil) ship their own keep rules;
# nothing in the app uses reflection of its own. Add a rule here only for a failure the release smoke test shows.

# Readable crash traces: the mapping file in the bundle de-obfuscates them; keep the line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
