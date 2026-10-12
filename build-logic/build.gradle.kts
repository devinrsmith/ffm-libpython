plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("jextract") {
            id = "ffm-proto.jextract"
            implementationClass = "JextractPlugin"
        }
    }
}
