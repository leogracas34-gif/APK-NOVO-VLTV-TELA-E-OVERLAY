plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("kotlin-kapt") // 🔥 Necessário para o Room funcionar
}

android {
    namespace = "com.vltv.play"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vltv.play"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        multiDexEnabled = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Essenciais
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // 🔥 ROOM DATABASE (Adicionado para Banco de Dados Local Ultra Rápido)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    // 🔥 FIREBASE
    implementation(platform("com.google.firebase:firebase-bom:32.7.2"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // Rede e Imagens
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0") // Para debug de rede
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // Player de Vídeo (Media3/ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0") 
    implementation("androidx.media3:media3-common:1.2.0")

    // Coroutines (Performance em segundo plano)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0") // Para escopo de coroutine na UI

    // ADICIONE ESSA LINHA PARA O BOTÃO CAST FUNCIONAR:
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")

    implementation("com.google.android.flexbox:flexbox:3.0.0")

   // Biblioteca para carregar imagens (Glide)
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    }
