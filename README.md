# Artçı

Motosiklet için ücretsiz navigasyon yardımcısı. Eski bir Android telefonu gösterge paneline çevirir: hız, bir sonraki dönüş ve müzik tek ekranda. Dikey ve yatay çalışır.

Artçı kendi başına rota hesaplamaz. Harita ve rota Yandex Navigasyon'da kalır; Artçı onun bildiriminden okuduğunu büyük ve sade gösterir, böylece sürerken navigasyon uygulamasını açmak gerekmez.

## Ne yapar

- GPS'ten hız kadranı
- Yandex Navigasyon'un bildiriminden sonraki dönüşü okur: mesafe, sokak adı, kalan yol ve varış saati
- Dönüşü perspektifli şematik bir yol üzerinde gösterir; yaklaştıkça dönüş sana doğru gelir
- Spotify kontrolü: kapak, şarkı adı, önceki / oynat / sonraki, ses − / +
- Renkler çalan şarkının kapağına göre değişir (ayarlardan sabit kırmızıya alınabilir)
- Saat, pil yüzdesi, konuma göre hava sıcaklığı (Open-Meteo), pil 40 °C'yi geçince uyarı
- Menü: Yandex, Spotify, simgeli uygulama listesi, ayarlar
- Ana ekran (launcher) olarak ayarlanabilir
- Sürüş kaydı: navigasyondan okunanlar, hız ve pil sıcaklığı bir dosyaya yazılır

Şematik yol gerçek yolun şekli değildir; yalnızca "ne kadar sonra, hangi yöne" bilgisini çizer. Yandex dönüş yönünü yazı olarak vermez, yalnızca simge gönderir. Artçı simgeyi bilinen şablonlarla karşılaştırır; tanıyamadığında Yandex'in kendi simgesini gösterir. Döner kavşaklarda kaçıncı çıkış olduğu bildirimde yoktur.

## Kurulum

Her gönderimde GitHub uygulamayı derler. Son APK:

https://github.com/philaeone/Deneme2/releases/latest/download/artci.apk

Telefon USB ile bağlıyken, APK'nın indiği klasörde:

```
adb install -r artci.apk
adb shell cmd notification allow_listener io.github.philaeone.artci/io.github.philaeone.artci.NotifService
adb shell pm grant io.github.philaeone.artci android.permission.ACCESS_FINE_LOCATION
adb shell dumpsys deviceidle whitelist +io.github.philaeone.artci
```

## Sürüş kaydı

Menü > Ayarlar > Sürüş kaydı son 500 satırı gösterir ve kopyalar. Dosyanın tamamı:

```
adb pull /sdcard/Android/data/io.github.philaeone.artci/files/surus-kaydi.txt
```

Kayıtta her navigasyon değişikliği, görülen her yeni simgenin şekli ve hareket halindeyken 30 saniyede bir hız ve pil durumu bulunur. Simge tanıma bu kayda bakılarak düzeltilir.

## Sorun ayıklama

Navigasyon paneli boş kalıyorsa: Menü > Ayarlar > Ham navigasyon verisi. O ekran, Yandex'in bildiriminden o an okunan her şeyi gösterir.

## Derleme

Saf Java, AndroidX yok. `gradle assembleDebug` yeterlidir; imza anahtarı depodadır, böylece her sürüm bir öncekinin üzerine kurulur.
