package com.docloom_android.feature.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.CreateTodoRequest
import com.docloom_android.core.model.Todo
import com.docloom_android.core.model.UpdateTodoRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.feature.tasks.TasksApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class TodosState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val todos: List<Todo> = emptyList(),
    val creating: Boolean = false,
    val error: String? = null,
)

/** Port of the website's TodoList: optimistic toggle and delete, reverted on failure. */
class TodosViewModel(private val api: TasksApi) : ViewModel() {
    private val _state = MutableStateFlow(TodosState())
    val state: StateFlow<TodosState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            when (val r = apiCall { api.todos() }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, todos = r.value.todos) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    fun create(title: String, onDone: () -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            when (val r = apiCall { api.createTodo(CreateTodoRequest(clean)) }) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(creating = false, todos = listOf(r.value.todo) + it.todos) }
                    onDone()
                }
                is ApiResult.Err -> _state.update { it.copy(creating = false, error = r.message) }
            }
        }
    }

    fun toggle(todo: Todo) {
        setDone(todo.id, !todo.isDone)
        viewModelScope.launch {
            val r = apiCall { api.updateTodo(todo.id, UpdateTodoRequest(!todo.isDone)) }
            if (r is ApiResult.Err) {
                setDone(todo.id, todo.isDone)
                _state.update { it.copy(error = r.message) }
            }
        }
    }

    private fun setDone(id: String, done: Boolean) = _state.update { s ->
        s.copy(todos = s.todos.map { if (it.id == id) it.copy(isDone = done) else it })
    }

    fun delete(todo: Todo) {
        val previous = _state.value.todos
        _state.update { s -> s.copy(todos = s.todos.filter { it.id != todo.id }) }
        viewModelScope.launch {
            val r = apiCall {
                val response = api.deleteTodo(todo.id)
                if (!response.isSuccessful) throw HttpException(response)
            }
            if (r is ApiResult.Err) _state.update { it.copy(todos = previous, error = r.message) }
        }
    }
}

/** Website /dashboard/todos: a personal checklist. */
@Composable
fun TodosScreen(vm: TodosViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }

    when {
        state.loading -> LoadingBox()
        state.loadError != null -> MessageBox("Couldn't load your todos", state.loadError, "Try again", { vm.load() })
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { if (it.length <= 200) title = it },
                        placeholder = { Text("Add a todo…") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.create(title) { title = "" } }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { vm.create(title) { title = "" } },
                        enabled = !state.creating && title.isNotBlank(),
                        shape = RoundedCornerShape(50),
                    ) { Text(if (state.creating) "Adding…" else "Add") }
                }
            }
            state.error?.let { item { ErrorBanner(it) } }
            item {
                if (state.todos.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        MutedText("No todos yet. Add your first one above.", Modifier.padding(24.dp))
                    }
                } else {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column {
                            state.todos.forEachIndexed { i, todo ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TodoRow(todo, onToggle = { vm.toggle(todo) }, onDelete = { vm.delete(todo) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: Todo, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = todo.isDone, onCheckedChange = { onToggle() })
        Text(
            todo.title,
            textDecoration = if (todo.isDone) TextDecoration.LineThrough else null,
            color = if (todo.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Delete ${todo.title}") }
    }
}
