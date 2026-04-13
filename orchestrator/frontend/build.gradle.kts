plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("22.15.0")
    download.set(true)
}

val assembleFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    outputs.dir(layout.buildDirectory.dir("dist"))
}
