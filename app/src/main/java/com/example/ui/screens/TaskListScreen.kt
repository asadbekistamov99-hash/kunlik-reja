package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Task
import com.example.data.TaskCategory
import com.example.ui.components.TaskCard
import com.example.ui.components.getCategoryInfo
import com.example.ui.viewmodel.TaskViewModel

@Composable
fun TaskListScreen(
    viewModel: TaskViewModel,
    onEditTask: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val allTasks by viewModel.allTasks.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val selectedTab = when(statusFilter) { "PENDING" -> 1; "COMPLETED" -> 2; else -> 0 }
    val activeCategoryFilter by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val priorityFilter by viewModel.selectedPriority.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val filteredTasks = allTasks.filter { task ->
        val matchesTab = when (selectedTab) {
            1 -> !task.isCompleted
            2 -> task.isCompleted
            else -> true
        }
        val matchesCat = activeCategoryFilter == null || task.category == activeCategoryFilter
        val matchesSearch = searchQuery.isEmpty() ||
                task.title.contains(searchQuery, ignoreCase = true) ||
                task.description.contains(searchQuery, ignoreCase = true) ||
                task.dateString.contains(searchQuery)

        matchesTab && matchesCat && matchesSearch && (priorityFilter == null || task.priority == priorityFilter)
    }.sortedBy { it.timestampMillis }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_task_list")
    ) {
        // Top Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Barcha Vazifalar Ro'yxati",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "Jami: ${allTasks.size} ta vazifa (${allTasks.count { it.isCompleted }} bajarilgan)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Search Bar inside Header
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Qidirish (sarlavha, sana)...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Status Tabs (Barchasi, Bajarilmagan, Bajarilgan)
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { viewModel.setStatusFilter("ALL") },
                text = { Text("Barchasi (${allTasks.size})") },
                modifier = Modifier.testTag("tab_all")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { viewModel.setStatusFilter("PENDING") },
                text = { Text("Bajarilmagan (${allTasks.count { !it.isCompleted }})") },
                modifier = Modifier.testTag("tab_pending")
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { viewModel.setStatusFilter("COMPLETED") },
                text = { Text("Bajarilgan (${allTasks.count { it.isCompleted }})") },
                modifier = Modifier.testTag("tab_completed")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Category Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = activeCategoryFilter == null,
                    onClick = { viewModel.setSelectedCategory(null) },
                    label = { Text("Barcha kategoriyalar") }
                )
            }
            items(TaskCategory.entries.toTypedArray()) { category ->
                val (catLabel, catIcon, catColor) = getCategoryInfo(category.name)
                val isSelected = activeCategoryFilter == category.name
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        viewModel.setSelectedCategory(if (isSelected) null else category.name)
                    },
                    label = { Text(catLabel) },
                    leadingIcon = { Icon(catIcon, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = catColor.copy(alpha = 0.2f),
                        selectedLabelColor = catColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Task List
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AssignmentTurnedIn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Mos vazifalar topilmadi",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onToggleComplete = { viewModel.toggleTaskCompletion(task) },
                        onDelete = { viewModel.deleteTask(task) },
                        onEdit = { onEditTask(task) }
                    )
                }
            }
        }
    }
}
