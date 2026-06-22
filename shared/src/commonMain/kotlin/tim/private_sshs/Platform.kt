package tim.private_sshs

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform