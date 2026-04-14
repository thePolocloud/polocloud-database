val major   = findProperty("polocloud.version.major")    as String? ?: "0"
val minor   = findProperty("polocloud.version.minor")    as String? ?: "0"
val patch   = findProperty("polocloud.version.patch")    as String? ?: "0"
val channel = (findProperty("polocloud.version.channel") as String? ?: "SNAPSHOT").uppercase()
val build   = findProperty("polocloud.version.build")    as String? ?: "local"

version = when (channel) {
    "RELEASE" -> "$major.$minor.$patch"
    else      -> "$major.$minor.$patch-${channel.lowercase()}.$build"
}