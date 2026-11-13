# Dominar Panel

Eski bir Android telefonu motosiklet gösterge paneline çeviren uygulama. Bajaj Dominar 400 için yazıldı, dikey ve yatay çalışır.

## Ne yapar

- GPS'ten hız göstergesi
- Yandex Navigasyon'un bildiriminden sonraki dönüşü okuyup büyük okla gösterir
- Spotify'ı kontrol eder: şarkı adı, kapak, önceki / oynat / sonraki
- Saat, pil yüzdesi, pil sıcaklığı
- Ana ekran (launcher) olarak ayarlanabilir

Harita ve rota hesaplama Yandex Navigasyon'da kalır; panel onun verdiği bilgiyi gösterir.

## Kurulum

Her gönderimde GitHub uygulamayı derler. Son APK:

https://github.com/philaeone/Deneme2/releases/latest/download/dominar-panel.apk

Telefon USB ile bağlıyken, APK'nın indiği klasörde:

```
adb install -r dominar-panel.apk
adb shell cmd notification allow_listener io.github.philaeone.dominarpanel/io.github.philaeone.dominarpanel.NotifService
adb shell pm grant io.github.philaeone.dominarpanel android.permission.ACCESS_FINE_LOCATION
adb shell dumpsys deviceidle whitelist +io.github.philaeone.dominarpanel
```

## Sorun ayıklama

Navigasyon paneli boş kalıyorsa: Menü > Ham navigasyon verisi. O ekran, Yandex'in bildiriminden okunan her şeyi gösterir.
