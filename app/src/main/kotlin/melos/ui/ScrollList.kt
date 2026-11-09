package melos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Список экранов: LazyColumn + вертикальный скроллбар + нижний fade (десктоп; на Android своя версия без него).
@Composable
fun ScrollList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, pal.card))))
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).padding(vertical = 12.dp, horizontal = 2.dp))
    }
}

// Прокручиваемое содержимое шторки (Sheets.SheetHost): на десктопе — со скроллбаром.
@Composable
fun SheetScroll(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).padding(vertical = 4.dp, horizontal = 2.dp))
    }
}
