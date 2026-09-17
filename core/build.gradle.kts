plugins { kotlin("jvm"); jacoco }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { dependsOn(tasks.test); reports { xml.required.set(true); html.required.set(true) } }
