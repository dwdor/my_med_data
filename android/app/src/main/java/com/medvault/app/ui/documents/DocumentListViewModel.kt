package com.medvault.app.ui.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medvault.app.data.local.entities.DocumentWithTags
import com.medvault.app.data.local.entities.TagEntity
import com.medvault.app.data.model.Category
import com.medvault.app.data.repository.DocumentRepository
import com.medvault.app.data.repository.TagRepository
import com.medvault.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentListViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    tagRepository: TagRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: Long = checkNotNull(savedStateHandle[Destinations.ARG_PROFILE_ID])

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<Category?>(null)
    val category: StateFlow<Category?> = _category.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    val tags: StateFlow<List<TagEntity>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _documents = MutableStateFlow<List<DocumentWithTags>>(emptyList())
    val documents: StateFlow<List<DocumentWithTags>> = _documents.asStateFlow()

    init {
        // Plain browsing (no query/tag) stays a live Room Flow; search recomputes as a one-shot
        // call on every debounced change (see DocumentRepository's kdoc for why).
        viewModelScope.launch {
            combine(_query, _category, _selectedTag, ::Triple)
                .debounce(250)
                .collectLatest { (query, category, tag) ->
                    if (query.isBlank() && tag == null) {
                        documentRepository.observeDocuments(profileId, category, fromDate = null, toDate = null)
                            .collect { _documents.value = it }
                    } else {
                        _documents.value = documentRepository.search(
                            profileId = profileId,
                            query = query,
                            category = category,
                            fromDate = null,
                            toDate = null,
                            tag = tag,
                        )
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onCategoryChange(newCategory: Category?) {
        _category.value = newCategory
    }

    fun onTagChange(newTag: String?) {
        _selectedTag.value = if (newTag == _selectedTag.value) null else newTag
    }
}
