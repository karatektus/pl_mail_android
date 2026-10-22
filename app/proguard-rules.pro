# R8 rules for the release build.
#
# Deliberately close to empty. Every rule here is a hole in the shrinker, so one
# gets added when something is demonstrably broken by minification — not
# pre-emptively, and not copied from a blog post. When a rule is added, the
# comment says which class broke and how it was reproduced, because a rule
# nobody can justify is a rule nobody can ever remove.

# slf4j 1.7, through PGPainless (#9). Its LoggerFactory looks for an optional
# binding class, org.slf4j.impl.StaticLoggerBinder, and falls back to logging
# nothing when there is none -- which is what this app wants: PGPainless has
# nothing to say that belongs in a phone's log, and some of what it could say
# is about keys. Nothing provides the class, so R8 stops at the reference;
# reproduced by assembleFossRelease once decryption made the logger reachable.
-dontwarn org.slf4j.impl.StaticLoggerBinder
