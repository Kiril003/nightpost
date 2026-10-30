package local.phantom.companion.feature.stream.ui.map

/**
 * ОДНА ПОВЕРХНЯ ЗНИЗУ ЗА РАЗ.
 *
 * Кадр тестувальника 11.09, дослівно — «налезло по верх»: аркуш пошуку
 * («Дім / Робота / швидкий доступ») відкритий, і поверх нього лежить картка
 * «PHANTOM · що корисного поруч…». Дві поверхні на одному краю екрана, кожна
 * зі своєю умовою показу: картка питала лише `mapAnswer.isLive`, а аркуш
 * відкривається наново дотиком по вкладці «Мапа» — і жодна з двох не знала
 * про іншу.
 *
 * Відповідь при цьому НЕ гасне: вона лишається живою під аркушем і вертається
 * тією ж миттю, коли аркуш закрили. Закривати чужу роботу за людину — гірше
 * за перекриття.
 */
internal object MapBottomSurface {

    fun answerVisible(answerLive: Boolean, searchOpen: Boolean): Boolean =
        answerLive && !searchOpen

    fun answerVisible(answerLive: Boolean, owner: MapBottomOwner): Boolean =
        answerLive && owner == MapBottomOwner.HOME_PEEK

    fun open(ui: MapUiState, owner: MapBottomOwner) {
        ui.searchOpen = owner == MapBottomOwner.SEARCH
        ui.toolsExpanded = owner == MapBottomOwner.LAYERS
        ui.aroundOpen = owner == MapBottomOwner.PEOPLE
        if (owner != MapBottomOwner.PEOPLE) {
            ui.whoSeesOpen = false
            ui.selectedPeer = null
            ui.peerGroup = null
            ui.meetOpen = false
            ui.meetPickOpen = false
            ui.myWorldOpen = false
        }
        if (owner != MapBottomOwner.PLACE) {
            ui.dossierTarget = null
            ui.selectedSavedPlace = null
            ui.droppedPoint = null
            ui.selectedLayerMarker = null
            ui.selectedLine = null
        }
    }

    fun resolve(
        ui: MapUiState,
        guiding: Boolean,
        hasRoute: Boolean,
    ): MapBottomOwner = resolveBottomOwner(ui, guiding, hasRoute)
}
