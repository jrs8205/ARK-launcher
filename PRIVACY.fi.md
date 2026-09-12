# ARK-launcherin tietosuojaseloste

*Päivitetty: 12.8.2026 · koskee ARK-launcherin versiota 0.7.12 ja uudempia
([version 0.7.11 seloste](https://github.com/jrs8205/ARK-launcher/blob/v0.7.11/PRIVACY.fi.md))*

*This policy in English: [PRIVACY.md](PRIVACY.md)*

ARK-launcher on ilmainen, avoimen lähdekoodin Android-kotinäyttö (Apache 2.0).
Tämä seloste kuvaa tarkalleen, mitä tietoja sovellus käsittelee. Lähdekoodi on
julkinen, joten jokaisen väitteen voi tarkistaa itse:
<https://github.com/jrs8205/ARK-launcher>.

## Lyhyt versio

- Kotinäytön asettelu, asetukset, sovelluslista ja ilmoitustiedot **pysyvät
  laitteellasi**. Sovelluksessa ei ole telemetriaa, analytiikkaa, mainoksia
  eikä minkäänlaista seurantaa.
- Verkkoa käytetään täsmälleen yhteen tarkoitukseen: sään näyttämiseen
  kellowidgetissä (mukaan lukien kuntanimen selvittäminen). Mikään muu
  sovelluksessa ei käytä internetiä.
- Sovelluksessa ei ole käyttäjätilejä, eikä se koskaan lähetä käyttötietoja,
  analytiikkaa tai mainostunnisteita kehittäjälle tai millekään kolmannelle
  osapuolelle.
- Versiosta 0.7.12 alkaen sovelluksessa ei ole sisäistä päivitintä eikä
  Google Drive -varmuuskopiointia: päivitykset tulevat valitsemastasi
  sovelluskaupasta (tai GitHubista), ja varmuuskopiot ovat paikallisia
  tiedostoja, jotka viet itse.

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
  Sovellus on jättäytynyt Androidin järjestelmävarmuuskopioinnin ulkopuolelle
  (`allowBackup="false"`), joten sen tietoja ei koskaan kopioida Google-tilisi
  laitevarmuuskopioon eikä siirretä laitteelta toiselle; viemäsi tiedosto on
  ainoa varmuuskopio.

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

Tämä on koko lista. Asetusten "Tarkista päivitykset" -rivi vain avaa GitHubin
julkaisusivun selaimessa — sovellus itse ei tee päivitystarkistuksia eikä
latauksia.

Kumpikaan pyyntö ei sisällä tilitietoja, evästeitä tai mainostunnisteita.
Kuten kaikki internet-liikenne, ne väistämättä paljastavat IP-osoitteesi
palvelimelle; pyynnöt tunnistautuvat yleisluontoisella
`ARK-launcher/<versio>`-User-Agent-otsakkeella, jossa ei ole laitetietoja.

## Luvat

| Lupa | Käyttötarkoitus |
|---|---|
| `QUERY_ALL_PACKAGES` | Asennettujen sovellusten listaus ja käynnistys — launcherin ydintehtävä |
| Ilmoitusten käyttöoikeus (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Pistemerkit, tilarivin kuvakkeet ja ilmoituswidget |
| Esteettömyyspalvelu (`BIND_ACCESSIBILITY_SERVICE`, valinnainen) | Ainoastaan tuplanapautuslukitus. Palvelu ei vastaanota esteettömyystapahtumia eikä voi lukea näytön sisältöä; se on olemassa vain näytön lukitsemista varten ja käynnissä vain, jos itse otat sen käyttöön |
| `READ_CALENDAR` | Seuraavan tapahtuman näyttäminen kellowidgetissä |
| `READ_CONTACTS` | Yhteystulokset sovellushaussa (valinnainen) |
| `READ_PHONE_STATE` | Signaalinvoimakkuus tilarivillä |
| `ACCESS_COARSE_LOCATION` | Sää kellowidgetissä |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `BLUETOOTH` (≤ Android 11) | Yhteysilmaisimet tilarivillä |
| `INTERNET` | Yllä kuvatut sääpyynnöt |
| `REQUEST_DELETE_PACKAGES` | Kuvakkeen pitkän painalluksen "poista asennus" -toiminto |
| `EXPAND_STATUS_BAR` | Ilmoitusten avaus alaspyyhkäisyllä |

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
