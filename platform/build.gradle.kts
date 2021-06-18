
plugins {
    `java-platform`
    `maven-publish`
}

dependencies {

    constraints {

        api(sciJava.bundles.all)
        api(imagej.bundles.all)
        api(imgLib2.bundles.all)
        api(scifio.bundles.all)
        api(fiji.bundles.all)
        api(bigDataViewer.bundles.all)
        api(trakEM2.bundles.all)
        api(n5.bundles.all)
        api(boneJ.bundles.all)
        api(ome.bundles.all)
        api(omero.bundles.all)
        api(groovy.bundles.all)
        api(apache.bundles.all)
        api(batik.bundles.all)
        api(commons.bundles.all)
        api(eclipseCollections.bundles.all)
        api(eclipseSwt.bundles.all)
        api(googleCloud.bundles.all)
        api(jackson.bundles.all)
        api(jetty.bundles.all)
        api(jGraphT.bundles.all)
        api(jna.bundles.all)
        api(jogamp.bundles.all)
        api(kotlib.bundles.all)
        api(logBack.bundles.all)
        api(migLayout.bundles.all)
        api(rSyntaxTextArea.bundles.all)
        api(slf4j.bundles.all)
        api(snakeYAML.bundles.all)
        api(tensorFlow.bundles.all)
        api(junit5.bundles.all)
        api(jmh.bundles.all)

        api(misc.bundles.all)
    }
}

//group = "sciJava"
//version = "30.0.0+6"
//
//publishing {
//    publications.create<MavenPublication>("maven") {
//        artifactId = "platform"
//        from(components["javaPlatform"])
//        suppressPomMetadataWarningsFor("runtimeElements")
//    }
//    repositories {
//        maven {
//            url = uri("$rootDir/../mary")
//        }
//    }
//}