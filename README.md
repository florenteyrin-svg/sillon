# Sillon

Serveur de musique auto-hébergé, simple et rapide, avec son appli Android (à venir).

- **Lit ta bibliothèque telle quelle** : FLAC, ALAC, MP3, AAC, Opus, WAV, AIFF, WavPack, APE, DSD…
  Tes fichiers ne sont jamais modifiés (dossier monté en lecture seule).
- **Ajout instantané** : un morceau qui arrive dans le dossier (par exemple téléchargé par à la carte)
  est dans la bibliothèque en ~2 secondes, sans scan à attendre.
- **Logo de qualité** pour chaque titre et album : `hires` (plus de 16 bit ou plus de 48 kHz),
  `cd` (16 bit / 44,1 kHz, 1411 kbps), `atmos` (Dolby Atmos) ou `lossy` (MP3, AAC…).
- **Pas de doublons Dolby Atmos** : les fichiers Atmos sont ignorés (les mêmes albums existent en FLAC).
  `SILLON_INCLUDE_ATMOS=true` dans le `.env` pour les garder.
- **Respecte les fichiers `.ignore`** comme Jellyfin : un dossier qui en contient un (Mes Découvertes,
  dossiers `cover` des pochettes animées) est ignoré.
- **Compatible Subsonic / OpenSubsonic** : Music Assistant (donc Home Assistant), Symfonium, etc.
- **Recherche et téléchargement Apple Music** via à la carte, sans exposer à la carte sur Internet.
  Ta version personnalisée d'à la carte est dans [`alacarte/`](alacarte/MES_MODIFS.md) (rangement Mes FLAC /
  Mes MP3 / Mes Dolby Atmos / Mes Playlists, Mes Découvertes, pochettes animées, clips).
- **Écoute directe depuis la recherche Apple Music** : appuyer sur un titre le lance en quelques secondes,
  pendant qu'il se télécharge dans un cache à part (hors de ton dossier de musiques, 10 Go max, les plus
  anciens effacés). La flèche le télécharge pour de bon dans ta bibliothèque.
- **Pochettes animées** comme Apple Music : celles qu'à la carte enregistre sont fournies à l'appli.
- **Playlists intelligentes** calculées en quelques millisecondes.
- **Playlist Favoris** : les titres marqués du cœur, avec les autres playlists (appli, Android Auto,
  Music Assistant). Les favoris Jellyfin s'importent depuis l'appli (Bibliothèque › Importer mes favoris Jellyfin).
- **Reprise de lecture** sur n'importe quel appareil (file d'attente et position gardées par le serveur).
- Léger : ~30 Mo de RAM pour 22 000 titres.

## Installation (ZimaOS ou tout Linux avec Docker)

1. Récupère le projet dans le dossier où tu ranges tes compose :
   ```bash
   git clone https://github.com/florenteyrin-svg/sillon.git
   ```
2. Copie le fichier de réglages et remplis-le :
   ```bash
   cd sillon && cp .env.example .env && nano .env
   ```
   - `MUSIC_PATH` : ton dossier de musiques.
   - `ALACARTE_DATA_PATH` : le dossier de données de ton à la carte actuel (garde la connexion Apple
     et les réglages).
   - `SILLON_ADMIN_PASSWORD` : le mot de passe de ton compte Sillon.
   - `ALACARTE_USER` / `ALACARTE_PASSWORD` : ton compte à la carte.
3. Si à la carte tourne déjà dans une autre pile Docker, arrête-la (mêmes noms et même port) :
   ```bash
   cd /chemin/de/ton/ancien/alacarte && docker compose down
   ```
4. Lance :
   ```bash
   docker compose up -d
   ```
   L'image de Sillon est téléchargée toute prête. À la carte est construit depuis le dossier
   `alacarte/` (quelques minutes la première fois).

Mettre à jour :

```bash
docker compose pull && docker compose up -d --build
```

### Nginx Proxy Manager : un seul domaine

Un seul « Proxy Host » : ton domaine → `IP-DU-SERVEUR`, port `4747`, avec SSL.
À la carte n'a pas besoin de domaine : l'appli passe par Sillon pour chercher et télécharger.

### Music Assistant

Ajoute le fournisseur de musique **Open Subsonic** :

| Champ | Valeur |
|---|---|
| Adresse | `http://IP-DU-SERVEUR` (ou ton domaine en `https://`) |
| Port | `4747` (ou `443` derrière Nginx) |
| Utilisateur / mot de passe | ceux de `SILLON_ADMIN_USER` / `SILLON_ADMIN_PASSWORD` |

## Appli Android

L'appli est dans [`android/`](android/) (Kotlin, Jetpack Compose, Media3) :

- **Écouter** : reprise de la lecture en cours, récemment écoutés (bibliothèque et Apple Music mélangés),
  playlists, ajouts récents.
- **Bibliothèque** : playlists (et intelligentes), artistes, albums, tous les titres, favoris.
- **Recherche** : ta bibliothèque et Apple Music. Un appui sur un titre Apple Music le lance tout de suite ;
  la flèche le télécharge dans ta bibliothèque.
- **Lecture** : logo HI-RES / CD / MP3, pochette animée, cœur Favoris, file d'attente, aléatoire, répétition.
  Un appui sur la pochette ou le titre ouvre l'album, sur le nom de l'artiste ouvre l'artiste.
- **Toujours prête** : la file d'attente et la position sont gardées sur le téléphone et rechargées avant
  tout le reste, donc rien à charger en ouvrant l'appli ou en montant en voiture.
- **Android Auto** : Récents, Playlists, Albums, Artistes, recherche et commande vocale.
- Lit FLAC, ALAC, MP3, AAC, Opus, WAV… (décodeurs FFmpeg intégrés).

L'APK est publié dans les [Releases](https://github.com/florenteyrin-svg/sillon/releases) quand la clé de
signature est enregistrée dans les secrets du dépôt (`SILLON_KEYSTORE_B64`, `SILLON_KEYSTORE_PASSWORD`).

## Pour les curieux

- [docs/architecture.md](docs/architecture.md) : comment c'est construit, l'API, la suite.
- Le serveur est écrit en Go (`server/`), une base SQLite dans le dossier de données.

## Feuille de route

1. ~~Serveur : bibliothèque, ajout instantané, Subsonic, à la carte, playlists intelligentes~~
2. Appli Android : ~~lecture, bibliothèque, recherche Apple Music, Android Auto, file gardée~~ ;
   à venir : widget vinyle, envoi vers Music Assistant, playlists hors ligne, paroles
3. Appli Windows, carte simple pour le mur Home Assistant
