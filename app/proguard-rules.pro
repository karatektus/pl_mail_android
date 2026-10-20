# R8 rules for the release build.
#
# Deliberately close to empty. Every rule here is a hole in the shrinker, so one
# gets added when something is demonstrably broken by minification — not
# pre-emptively, and not copied from a blog post. When a rule is added, the
# comment says which class broke and how it was reproduced, because a rule
# nobody can justify is a rule nobody can ever remove.

# ML Kit's component registrars, foss flavour (#1). ML Kit builds its
# components by instantiating every registrar named in the manifest through
# its no-argument constructor, by reflection. Nothing calls those constructors
# directly, so R8 removed them: the classes stayed, the constructors went, and
# the foss build crashed the moment the QR scanner asked for a client --
# "Could not instantiate ...CommonComponentRegistrar", caused by
# NoSuchMethodException <init>, then a NullPointerException in
# BarcodeScanning.getClient(). The google flavour never broke because
# firebase-common ships exactly this rule for every ComponentRegistrar and it
# covers ML Kit's as well; the foss build has no Firebase and so no rule.
# Reproduced on an Android 17 emulator with the v0.0.31 foss release APK, and
# gone with this rule.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
