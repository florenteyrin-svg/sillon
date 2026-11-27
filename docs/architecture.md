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

## Serveur (`server/`)

| Paquet | Rôle |
|---|---|
| `scanner` | Parcourt `/music`, lit les tags et la qualité (FLAC en natif, le reste via ffprobe), suit les changements avec inotify. Un fichier doit rester 2 s sans changer avant d'être lu, pour ne pas lire un téléchargement à moitié écrit. Un scan complet tourne au démarrage puis toutes les 6 h (`SILLON_RESCAN_HOURS`) par sécurité ; seuls les fichiers modifiés sont relus. |
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

- `quality` sur les titres et albums : `hires`, `cd` ou `lossy` (le logo à afficher).
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
| GET | `/api/catalog/search?q=` | Recherche Apple Music ; chaque album/titre porte `inLibrary` (+ `libraryId`) |
| GET | `/api/catalog/album/{id}` | Détail d'un album Apple Music |
| GET | `/api/catalog/artist/{id}` | Détail d'un artiste Apple Music |
| POST | `/api/catalog/download` | `{"type":"album"\|"song"\|"playlist","id":"…"}` |
| GET | `/api/catalog/jobs` | Téléchargements en cours et récents |
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

## Suite : mix DJ

Pas pendant la lecture sur le téléphone, et pas besoin d'analyser toute la bibliothèque d'avance :

1. **Analyse légère de chaque titre** (tempo, tonalité, début/fin réels, fin des voix) :
   ~1–2 s par titre, une fois, puis à chaque ajout.
2. **Séparation voix / batterie / basse / mélodie seulement sur la zone de transition**
   (~45 s de fin + ~45 s de début), préparée pendant que le titre précédent joue,
   sur la Quadro si elle est libre, sinon sur le processeur.
3. Le serveur rend le passage mixé ; le téléphone enchaîne fin du titre A → transition → titre B.
4. Cache limité en taille (les transitions les plus anciennes sont effacées), les FLAC ne sont jamais touchés.
