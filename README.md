# Dominar Panel

Eski bir Android telefonu motosiklet gösterge paneline çeviren uygulama. Bajaj Dominar 400 için yazıldı, dikey ve yatay çalışır.

## Ne yapar

- GPS'ten hız göstergesi
- Yandex Navigasyon'un bildiriminden sonraki dönüşü okur; mesafeye göre yaklaşan şematik bir yol çizer
- Spotify'ı kontrol eder: kapak, şarkı adı, önceki / oynat / sonraki, ses − / +
- Panelin renkleri çalan şarkının kapağına göre değişir
- Saat, pil yüzdesi, konuma göre hava sıcaklığı (Open-Meteo), pil 40 °C'yi geçince uyarı
- Menü: Yandex, Spotify, simgeli uygulama listesi, ayarlar
- Ana ekran (launcher) olarak ayarlanabilir

Harita ve rota hesaplama Yandex Navigasyon'da kalır; panel onun verdiği bilgiyi gösterir. Şematik yol gerçek yolun şekli değildir, yalnızca "ne kadar sonra, hangi yöne" bilgisini çizer.

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

Navigasyon paneli boş kalıyorsa: Menü > Ayarlar > Ham navigasyon verisi. O ekran, Yandex'in bildiriminden okunan her şeyi gösterir.
