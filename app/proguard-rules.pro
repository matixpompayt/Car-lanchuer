# CAR OS – R8 tylko optymalizuje (Compose, Kotlin, androidx), niczego z aplikacji nie wycina.

# czytelne nazwy w dzienniku błędów (Diag / CrashLog)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# cały kod CAR OS i wspólny moduł core (odbiorniki, usługi, refleksja na klasach systemu radia)
-keep class pl.mati.** { *; }

# biblioteki z kodem natywnym / własnymi mechanizmami ładowania – bez zmian
-keep class org.maplibre.** { *; }
-keep class org.osmdroid.** { *; }
-keep class com.google.zxing.** { *; }

# brakujące opcjonalne zależności bibliotek nie przerywają budowania
-dontwarn **
-ignorewarnings
