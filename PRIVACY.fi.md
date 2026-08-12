# ARK-launcherin tietosuojaseloste

*Päivitetty: 12.8.2026 · koskee ARK-launcherin versiota 0.7.11 ja uudempia*

*This policy in English: [PRIVACY.md](PRIVACY.md)*

ARK-launcher on ilmainen, avoimen lähdekoodin Android-kotinäyttö (Apache 2.0).
Tämä seloste kuvaa tarkalleen, mitä tietoja sovellus käsittelee. Lähdekoodi on
julkinen, joten jokaisen väitteen voi tarkistaa itse:
<https://github.com/jrs8205/ARK-launcher>.

## Lyhyt versio

- Kotinäytön asettelu, asetukset, sovelluslista ja ilmoitustiedot **pysyvät
  laitteellasi**. Sovelluksessa ei ole telemetriaa, analytiikkaa, mainoksia
  eikä minkäänlaista seurantaa.
- Verkkoa käytetään vain alla kohdassa *Verkon käyttö* lueteltuihin
  tarkoituksiin: sään näyttämiseen kellowidgetissä (mukaan lukien kuntanimen
  selvittäminen), sovelluspäivitysten tarkistamiseen GitHubista sekä — vain
  jos otat sen käyttöön — asettelun varmuuskopiointiin *omaan* Google
  Driveesi.
- Sovelluksessa ei ole käyttäjätilejä, eikä se koskaan lähetä käyttötietoja,
  analytiikkaa tai mainostunnisteita kehittäjälle tai millekään kolmannelle
  osapuolelle.

## Laitteelle tallennettavat tiedot

- **Kotinäytön asettelu** — kuvakkeiden, kansioiden ja widgetien sijainnit;
  tallennetaan paikalliseen tietokantaan.
- **Asetukset** — ruudukon koko, eleet, kuvakepaketti, piilotetut sovellukset,
  omat sovellusnimet, käytetyimmät-järjestys ja vastaavat; tallennetaan
  paikallisesti.
- **Ilmoitustiedot** — jos myönnät ilmoitusten käyttöoikeuden, ilmoituksia
  luetaan ainoastaan pistemerkkien, tilarivin kuvakkeiden ja valinnaisen
  ilmoituswidgetin näyttämiseen. Ilmoitusten sisältö käsitellään laitteen
  muistissa, eikä sitä koskaan tallenneta pysyvästi tai lähetetä minnekään.
- **Varmuuskopiot** — viemäsi varmuuskopio kirjoitetaan valitsemaasi
  tiedostosijaintiin. Se sisältää asettelusi ja asetuksesi, ei mitään muuta.

## Verkon käyttö

1. **Sää** (vain kun särivi on käytössä ja sijaintilupa on myönnetty):
   sovellus hakee lämpötilan ja säätilan avaimettomasta
   [Open-Meteo](https://open-meteo.com)-rajapinnasta. Koordinaatit pyöristetään
   noin kilometrin tarkkuuteen ennen pyyntöä; tarkka sijainti ei koskaan
   poistu laitteelta.
2. **Kuntanimi** sään vieressä: nimi selvitetään ensisijaisesti laitteen
   omalla geokooderilla. Jos laitteessa ei ole toimivaa geokooderia (joissakin
   malleissa ei ole), sovellus käyttää varalla avaimetonta
   [BigDataCloud](https://www.bigdatacloud.com)-käänteisgeokoodausrajapintaa
   ja lähettää sinne samat ~1 km:n tarkkuuteen pyöristetyt koordinaatit sekä
   laitteen käyttöliittymäkielen — enintään kerran 30 minuutin säähakua
   kohden, ja vain kun paikallinen geokooderi epäonnistui.
3. **Päivitystarkistus**: sovellus kysyy tämän julkisen GitHub-repositorion
   Releases-rajapinnasta, onko uudempi versio olemassa, ja lataa uuden APK:n
   GitHubista, kun sinä pyydät. Pelkkiä GET-pyyntöjä.
4. **Google Drive -varmuuskopiointi** (valinnainen, oletuksena **pois
   päältä**): jos otat Drive-varmuuskopioinnin käyttöön, sovellus lähettää
   varmuuskopiotiedoston *oman* Google Drivesi piilotettuun sovelluskohtaiseen
   tila-alueeseen (`appDataFolder`, OAuth-scope `drive.appdata`). Tämä scope
   antaa sovellukselle pääsyn **vain sen omiin varmuuskopiotiedostoihin**, ei
   mihinkään muuhun Drivessäsi; kehittäjällä ei ole pääsyä mihinkään niistä.
   Kolme käytännön seurausta:
   - Varmuuskopiot **eivät näy** Driven tavallisessa tiedostolistassa. Näet
     niiden tilankäytön ja voit poistaa ne kaikki Driven kohdasta
     **Asetukset → Sovellusten hallinta** (drive.google.com).
   - Sovellus säilyttää vain **3 uusinta** varmuuskopiotiedostoa: jokaisen
     onnistuneen lähetyksen jälkeen se poistaa automaattisesti luomansa
     vanhemmat varmuuskopiotiedostot, jotta vanhoja palautuspisteitä ei
     kerry — vanhimmat poistetaan.
   - Ominaisuuden poistaminen käytöstä lopettaa sekä lähetykset että
     automaattisen poiston.

Mikään näistä pyynnöistä ei sisällä tilitietoja, evästeitä tai
mainostunnisteita. Kuten kaikki internet-liikenne, ne kuitenkin
väistämättä paljastavat IP-osoitteesi palvelimelle, ja Androidin
sisäänrakennetun HTTP-asiakkaan pyynnöissä kulkee Androidin oletusarvoinen
`User-Agent`-otsake, joka kertoo Android-version ja laitemallin.

## Luvat

| Lupa | Käyttötarkoitus |
|---|---|
| `QUERY_ALL_PACKAGES` | Asennettujen sovellusten listaus ja käynnistys — launcherin ydintehtävä |
| Ilmoitusten käyttöoikeus (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Pistemerkit, tilarivin kuvakkeet ja ilmoituswidget |
| `READ_CALENDAR` | Seuraavan tapahtuman näyttäminen kellowidgetissä |
| `READ_CONTACTS` | Yhteystulokset sovellushaussa (valinnainen) |
| `READ_PHONE_STATE` | Signaalinvoimakkuus tilarivillä |
| `ACCESS_COARSE_LOCATION` | Sää kellowidgetissä |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `BLUETOOTH` (≤ Android 11) | Yhteysilmaisimet tilarivillä |
| `INTERNET` | Sää, päivitystarkistukset, valinnainen Drive-varmuuskopiointi |
| `POST_NOTIFICATIONS` | Päivitys- ja varmuuskopioilmoitukset |
| `REQUEST_INSTALL_PACKAGES` | Hyväksymiesi päivitysten asennus sovelluksen omalla päivittimellä |
| `REQUEST_DELETE_PACKAGES` | Kuvakkeen pitkän painalluksen "poista asennus" -toiminto |
| `EXPAND_STATUS_BAR` | Ilmoitusten avaus alaspyyhkäisyllä |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | Androidin WorkManager-kirjaston automaattisesti lisäämiä; kirjasto ajaa ajastetun Drive-varmuuskopioinnin (pitää laitteen hereillä lähetyksen ajan ja ajastaa työn uudelleen käynnistyksen jälkeen). Ei käytetä mihinkään muuhun |

Kalenteri-, yhteystieto- ja puhelintilatiedot luetaan tarvittaessa yllä
kuvattuja ominaisuuksia varten, niitä käytetään vain laitteella, eikä niitä
koskaan tallenneta muistivälimuistia pidemmälle tai lähetetä minnekään.
Sijaintia käsitellään laitteella samalla tavalla; ainoa laitteelta poistuva
sijaintitieto on ~1 km:n tarkkuuteen pyöristetyt koordinaatit, jotka
lähetetään kohdassa *Verkon käyttö* kuvatuille sää- ja
käänteisgeokoodauspalveluille.

## Lapset

ARK-launcher on yleiskäyttöinen apuohjelma, jossa ei ole omaa sisältöä,
mainoksia eikä ostoja. Sitä ei ole suunnattu lapsille, eikä se tietoisesti
kerää henkilötietoja lapsilta — eikä keneltäkään muultakaan: sovelluksessa ei
ole tilejä, eikä se kerää henkilötietoja lainkaan.

## Muutokset ja yhteydenotot

Tähän selosteeseen tehtävät muutokset tehdään julkisessa repositoriossa, jossa
koko muutoshistoria on näkyvissä. Kysymykset ja ilmoitukset:
<https://github.com/jrs8205/ARK-launcher/issues>.
