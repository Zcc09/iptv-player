package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.Episode
import com.zcc09.iptvplayer.core.MediaKind
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.VodCategory
import com.zcc09.iptvplayer.core.VodItem

/**
 * Films and series (the "Movies" and "Shows" tabs).
 *
 * Panels expose huge catalogues - the test server alone serves 72k films in a
 * single 38 MB response - so this never asks for "everything": it loads the
 * category list once, then the chosen category's items on demand.
 */

internal fun vodCatKey(playlistId: String, kind: MediaKind) = "$playlistId|${kind.name}"

internal fun vodItemKey(playlistId: String, kind: MediaKind, categoryId: String) =
    "$playlistId|${kind.name}|$categoryId"

@Composable
internal fun VodTabRow(selected: MediaKind, onSelect: (MediaKind) -> Unit) {
    TabRow(selectedTabIndex = selected.ordinal) {
        VodTabItem(selected, onSelect, MediaKind.LIVE, "Live")
        VodTabItem(selected, onSelect, MediaKind.MOVIE, "Movies")
        VodTabItem(selected, onSelect, MediaKind.SERIES, "Shows")
    }
}

@Composable
private fun VodTabItem(
    selected: MediaKind,
    onSelect: (MediaKind) -> Unit,
    kind: MediaKind,
    label: String
) {
    Tab(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        text = { Text(label) }
    )
}

/** Films and series own screen, with the same Live/Movies/Shows switcher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VodScreen(playlistId: String, kind: MediaKind, onTab: (MediaKind) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (kind == MediaKind.MOVIE) "Movies" else "Shows") },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            VodTabRow(selected = kind, onSelect = onTab)
            VodTab(playlistId, kind)
        }
    }
}

@Composable
internal fun VodTab(playlistId: String, kind: MediaKind) {
    val categories by Repo.vodCategories.collectAsState()
    val itemsMap by Repo.vodItems.collectAsState()
    val busySet by Repo.vodBusy.collectAsState()
    val errors by Repo.vodErrors.collectAsState()

    val cats = categories[vodCatKey(playlistId, kind)].orEmpty()
    var query by remember(kind) { mutableStateOf("") }
    var selected by remember(playlistId, kind) { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId, kind) {
        if (cats.isEmpty()) Repo.loadVodCategoriesAsync(playlistId, kind)
    }
    // Nothing renders until a category is chosen, so choose the first one.
    LaunchedEffect(cats) {
        if (selected == null && cats.isNotEmpty()) selected = cats.first().id
    }

    val categoryId = selected
    val loadingCats = busySet.contains(vodCatKey(playlistId, kind))
    val loadingItems = categoryId != null &&
        busySet.contains(vodItemKey(playlistId, kind, categoryId))
    val items = categoryId?.let { itemsMap[vodItemKey(playlistId, kind, it)] }.orEmpty()
    val noun = if (kind == MediaKind.MOVIE) "movies" else "shows"

    LaunchedEffect(playlistId, kind, categoryId) {
        if (categoryId != null && !itemsMap.containsKey(vodItemKey(playlistId, kind, categoryId))) {
            Repo.loadVodItemsAsync(playlistId, kind, categoryId)
        }
    }

    // One search box narrows the category strip AND the list: a panel can expose
    // hundreds of categories, so hunting for one by scrolling is hopeless.
    val chipCats = remember(cats, query) {
        if (query.isBlank()) cats
        else cats.filter { it.name.contains(query, ignoreCase = true) }
    }
    val shown = remember(items, query) {
        if (query.isBlank()) items
        else items.filter { it.name.contains(query, ignoreCase = true) }
    }

    VodSearchField(
        query = query,
        onChange = { query = it },
        placeholder = if (kind == MediaKind.MOVIE) "Search movies" else "Search shows"
    )

    when {
        loadingCats && cats.isEmpty() -> VodLoading("Loading categories…")

        cats.isEmpty() -> {
            val key = vodCatKey(playlistId, kind)
            VodEmpty(
                title = "No ${if (kind == MediaKind.MOVIE) "movie" else "show"} categories",
                detail = errors[key] ?: "This account has no VOD catalogue, or it is empty.",
                isError = errors[key] != null,
                onRetry = { Repo.loadVodCategoriesAsync(playlistId, kind) }
            )
        }

        else -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(chipCats, key = { it.id }) { c: VodCategory ->
                    FilterChip(
                        selected = categoryId == c.id,
                        onClick = { selected = c.id },
                        label = { Text(c.name) }
                    )
                }
                if (chipCats.isEmpty()) {
                    item {
                        Text(
                            "no categories match \"$query\"",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            val current = cats.firstOrNull { it.id == categoryId }
            VodCountLine(
                when {
                    loadingItems -> "Loading $noun…"
                    current != null -> "${shown.size} of ${items.size} in ${current.name}"
                    else -> "${cats.size} categories"
                }
            )

            when {
                loadingItems && items.isEmpty() -> VodLoading("Loading $noun…")

                items.isEmpty() -> {
                    val key = vodItemKey(playlistId, kind, categoryId ?: "")
                    VodEmpty(
                        title = "Nothing in this category",
                        detail = errors[key] ?: "Pick another category, or try again.",
                        isError = errors[key] != null,
                        onRetry = {
                            categoryId?.let { Repo.loadVodItemsAsync(playlistId, kind, it) }
                        }
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(shown, key = { "${kind.name}-${it.id}" }) { item ->
                        VodRow(
                            item = item,
                            onOpen = {
                                if (kind == MediaKind.MOVIE) {
                                    playMovie(playlistId, item, current?.name)
                                } else {
                                    Nav.push(Screen.Series(playlistId, item.id, item.name))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** A film is just a stream URL with a poster, so it plays straight away. */
internal fun playMovie(playlistId: String, item: VodItem, category: String?) {
    val url = Repo.movieUrl(playlistId, item)
    if (url.isNullOrBlank()) {
        Repo.postStatus("Could not build a playback URL for ${item.name}")
        return
    }
    val channel = Channel(
        id = "movie:$playlistId:${item.id}",
        playlistId = playlistId,
        name = item.name,
        group = category ?: "",
        logo = item.icon,
        url = url,
        streamId = item.id
    )
    Nav.push(Screen.Player(channel))
}

@Composable
private fun VodRow(item: VodItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteImage(
            url = item.icon,
            contentDescription = item.name,
            size = 44.dp,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            val sub = when {
                item.kind == MediaKind.SERIES -> "Series"
                item.containerExtension.isNotBlank() -> item.containerExtension.uppercase()
                else -> ""
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = if (item.kind == MediaKind.SERIES) {
                Icons.Default.KeyboardArrowRight
            } else {
                Icons.Default.PlayArrow
            },
            contentDescription = if (item.kind == MediaKind.SERIES) "Open series" else "Play",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun VodSearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun VodCountLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun VodLoading(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VodEmpty(title: String, detail: String, isError: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Seasons and episodes of one series; tapping an episode plays it. */
@Composable
internal fun SeriesScreen(playlistId: String, seriesId: Int, title: String) {
    var episodes by remember(seriesId) { mutableStateOf<List<Episode>?>(null) }

    LaunchedEffect(playlistId, seriesId) {
        episodes = Repo.loadEpisodes(playlistId, seriesId)
    }

    val list = episodes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                list == null -> VodLoading("Loading episodes…")
                list.isEmpty() -> VodEmpty(
                    title = "No episodes found",
                    detail = "The panel returned no episodes for this series.",
                    isError = false,
                    onRetry = { episodes = null }
                )

                else -> {
                    VodCountLine("${list.size} episodes")
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        val bySeason = list.groupBy { it.season }.toSortedMap()
                        bySeason.forEach { (season, eps) ->
                            item(key = "season-$season") {
                                Text(
                                    "Season $season",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    )
                                )
                            }
                            items(eps, key = { "ep-${it.id}" }) { ep ->
                                EpisodeRow(
                                    episode = ep,
                                    onPlay = { playEpisode(playlistId, title, ep) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun playEpisode(playlistId: String, seriesTitle: String, ep: Episode) {
    val url = Repo.episodeUrl(playlistId, ep) ?: return
    val label = ep.title.ifBlank { "$seriesTitle S${ep.season}E${ep.number}" }
    val channel = Channel(
        id = "episode:$playlistId:${ep.id}",
        playlistId = playlistId,
        name = label,
        group = seriesTitle,
        url = url,
        streamId = ep.id
    )
    Nav.push(Screen.Player(channel))
}

@Composable
private fun EpisodeRow(episode: Episode, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "E${episode.number}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                episode.title.ifBlank { "Episode ${episode.number}" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            if (episode.containerExtension.isNotBlank()) {
                Text(
                    episode.containerExtension.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play episode",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
