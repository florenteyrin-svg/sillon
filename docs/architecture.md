# Architecture

```
            Nginx Proxy Manager (un seul domaine)
                         │
                   ┌─────▼─────┐   réseau Docker interne   ┌──────────────┐
 Appli Android ───▶│  Sillon   │──────────────────────────▶│  à la carte  │──▶ Apple Music
 Music Assistant ─▶│  :4747    │                           │  :7373       │
 Symfonium ───────▶└─────┬─────┘                           └──────┬───────┘
                         │ lecture seule                          │ écrit les FLAC
                         ▼                                        ▼
                     /music  ◀───────────── même dossier ─────────┘
```

À la carte (dossier `alacarte/`) est la version personnalisée décrite dans `alacarte/MES_MODIFS.md`.
Les fichiers du module Apple (`wrapper/rootfs/system`, ~116 Mo, inchangés) ne sont pas copiés ici :
le Dockerfile du wrapper les récupère dans le dépôt d'origine à un commit fixe.

## Serveur (`server/`)

| Paquet | Rôle |
|---|---|
| `scanner` | Ignore les dossiers contenant un fichier `.ignore` (et les dossiers cachés). Parcourt `/music`, lit les tags et la qualité (FLAC en natif, le reste via ffprobe), suit les changements avec inotify. Un fichier doit rester 2 s sans changer avant d'être lu, pour ne pas lire un téléchargement à moitié écrit. Un scan complet tourne au démarrage puis toutes les 6 h (`SILLON_RESCAN_HOURS`) par sécurité ; seuls les fichiers modifiés sont relus. |
| `db` | SQLite (WAL) : titres, albums, artistes, comptes, playlists, favoris, écoutes, file d'attente. |
| `subsonic` | API Subsonic 1.16.1 + extensions OpenSubsonic (`apiKeyAuthentication`, `songLyrics`, `formPost`). Paroles lues depuis les `.lrc` posés par à la carte. |
| `api` | API propre aux applis Sillon (ci-dessous). |
| `alacarte` | Client d'à la carte (connexion par compte, recherche, téléchargements, file). |
| `smart` | Règles des playlists intelligentes → une requête SQL. |
| `events` | Diffusion des événements en direct (Server-Sent Events). |

### Identifiants

Stables et préfixés : `tr-` titre (chemin du fichier), `al-` album (artiste de l'album + nom),
`ar-` artiste, `pl-` playlist. Un album réparti sur plusieurs dossiers reste un seul album.

### Champs ajoutés aux réponses Subsonic

- `quality` sur les titres et albums : `hires`, `cd`, `atmos` ou `lossy` (le logo à afficher).
  La version Dolby Atmos d'un album reste un album séparé.
- `animatedCover: true` sur les titres et albums qui ont une pochette animée (`cover/animated.mp4`
  dans le dossier de l'album, enregistrée par à la carte).
- `smart` sur les playlists.

## API Sillon (`/api`)

Connexion identique à Subsonic : `Authorization: Bearer <clé API>` (clé donnée par `/api/me`),
Basic, ou paramètres `u`/`p` (ou `t`/`s`).

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/health` | État (sans connexion) |
| GET | `/api/me` | Compte et clé API |
| GET | `/api/events` | Flux d'événements : `library.added`, `library.removed`, `library.scan`, `download.job` |
| GET | `/api/library/status` | Nombre de titres, albums, répartition des qualités |
| GET | `/api/library/changes?since=<unix>` | Titres ajoutés depuis, pour une mise à jour locale rapide |
| GET | `/api/animated-cover/{id}` | Pochette animée (vidéo MP4 en boucle) d'un album ou d'un titre |
| GET | `/api/catalog/search?q=` | Recherche Apple Music ; chaque album/titre porte `inLibrary` (+ `libraryId`) |
| GET | `/api/catalog/album/{id}` | Détail d'un album Apple Music |
| GET | `/api/catalog/artist/{id}` | Détail d'un artiste Apple Music |
| POST | `/api/catalog/download` | `{"type":"album"\|"song"\|"playlist","id":"…"}` |
| GET | `/api/catalog/jobs` | Téléchargements en cours et récents |
| POST | `/api/catalog/play` | Écoute directe d'un titre Apple Music `{"id","albumId","title","artist","album","artwork","duration"}` → `{"url"}` |
| GET | `/api/catalog/stream/{id}` | Le son du titre (MP4/ALAC), envoyé pendant qu'il se télécharge, puis depuis le cache |
| GET | `/api/recent` | Récemment écoutés : titres de la bibliothèque et du catalogue mélangés |
| GET | `/api/mix/transition?from=&to=` | Passage Mix DJ entre deux titres : `{"status","aOut","bIn","url","style"}` |
| GET | `/api/mix/file/{key}` | Le passage (FLAC) |
| GET/POST | `/api/smart-playlists` | Liste / création `{"name","rules"}` |
| PUT | `/api/smart-playlists/{id}` | Modification |
| POST | `/api/smart-playlists/preview` | Nombre de titres d'un jeu de règles |

### Règles des playlists intelligentes

```json
{
  "match": "all",
  "rules": [
    {"field": "genre", "op": "contains", "value": "rap"},
    {"field": "year", "op": "between", "value": [2000, 2010]},
    {"field": "added", "op": "inlast", "value": 30},
    {"field": "quality", "op": "is", "value": "hires"},
    {"field": "starred", "op": "is", "value": true}
  ],
  "sort": "random",
  "limit": 200
}
```

- Champs texte : `title artist album albumartist genre path quality suffix`
  (opérateurs `is isnot contains notcontains startswith`).
- Champs nombre : `year duration playcount bitdepth samplerate track` (`is isnot gt lt between`).
- Dates : `added lastplayed` (`inlast notinlast`, en jours).
- `starred` : `true` / `false`.
- Tri : `random added added_asc plays last_played title artist year`.

## Écoute directe (catalogue Apple Music)

1. L'appli appelle `POST /api/catalog/play`. Sillon demande à à la carte (`POST /api/stream/song/{id}`,
   ajouté dans `alacarte/backend/routes/stream.mjs`) de récupérer le titre dans `/cache/{id}/`, jamais
   dans la bibliothèque.
2. L'outil de téléchargement est réglé pour écrire le fichier au fil du déchiffrement
   (`max-memory-limit: 0`) ; Sillon envoie ce fichier qui grandit au téléphone (`/api/catalog/stream/{id}`).
   Le son démarre dès les premiers fragments, quelques secondes après l'appui.
3. Réécouter le titre = lecture depuis le cache, instantanée et avec avance rapide.
4. Le cache garde sa taille sous `SILLON_CACHE_MAX_GB` (10 Go) : les titres écoutés le moins récemment
   partent en premier. Télécharger un titre avec la flèche le retire du cache.

## Mix DJ

Conteneur `sillon-dj` (`dj/sillon_dj.py`, Python : librosa, Demucs, rubberband via ffmpeg).

1. L'appli demande `GET /api/mix/transition?from=<titre en cours>&to=<suivant>` (et la suivante d'avance).
   Sillon transmet au conteneur DJ, qui calcule en tâche de fond ; l'appli redemande jusqu'à `done`.
2. Seules la fin de A (~50 s, silence final coupé) et le début de B (~50 s) sont traités :
   - tempo et battements (librosa) ; si les tempos sont proches (8 %, double/demi-temps compris), B est
     étiré au tempo de A sans changer sa hauteur (rubberband) ;
   - séparation voix / batterie / basse / reste (Demucs `htdemucs`, carte NVIDIA si utilisable, sinon
     processeur ; si la carte est occupée, par Jellyfin par exemple, le processeur prend le relais) ;
   - mix sur 16 mesures calé sur les temps : la batterie de B arrive, les basses s'échangent à la 8e mesure,
     la mélodie de A s'en va, la voix de A continue sur la musique de B puis la voix de B prend le relais ;
   - le passage commence exactement sur le son d'origine de A et finit sur B à sa vitesse normale.
   - Tempos trop différents : fondu enchaîné de 10 s.
3. Réponse : `aOut` (où quitter A), `bIn` (où reprendre B), `url` du passage (FLAC, `/api/mix/file/…`).
4. L'appli joue A coupé à `aOut` → le passage → B à partir de `bIn`.
5. Les passages sont gardés dans `<cache>/mix`, effacés après 14 jours sans écoute. Les FLAC ne sont jamais touchés.

Mesure : ~30–40 s de calcul par passage sur un processeur 4 cœurs, sans carte graphique.
