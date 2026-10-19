# Craffa uses no reflection-based serialization (JSON is hand-mapped),
# so the default optimize rules are enough. OkHttp ships its own rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
