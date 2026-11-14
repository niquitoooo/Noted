package com.jot.app.data.db

import com.jot.app.data.Note
import com.jot.app.data.NotesJson
import org.json.JSONArray

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    html = body,
    text = plainText,
    links = linkText,
    summary = summary,
    hasTodo = hasTodo,
    notebook = notebook,
    tags = JSONArray(tags).toString(),
    pinned = pinned,
    trashed = trashed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
    attachments = NotesJson.attachmentsToJson(attachments).toString(),
    bookmarks = NotesJson.bookmarksToJson(bookmarks).toString(),
    icon = icon,
    trashedWith = trashedWith,
    noteLinkIds = JSONArray(noteLinkIds).toString(),
    revision = revision,
    legacyMarkdown = legacyMarkdown,
    cover = cover,
    coverX = coverX,
    coverY = coverY,
    coverScale = coverScale,
    searchKey = SearchKey.of(title, plainText, linkText)
)

fun NoteEntity.toNote(): Note = Note(
    id = id,
    title = title,
    body = html,
    notebook = notebook,
    tags = strings(tags),
    pinned = pinned,
    trashed = trashed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
    attachments = NotesJson.attachmentsFromJson(JSONArray(attachments), id),
    bookmarks = NotesJson.bookmarksFromJson(JSONArray(bookmarks)),
    icon = icon,
    trashedWith = trashedWith,
    plainText = text,
    linkText = links,
    summary = summary,
    hasTodo = hasTodo,
    noteLinkIds = strings(noteLinkIds),
    revision = revision,
    legacyMarkdown = legacyMarkdown,
    cover = cover,
    coverX = coverX,
    coverY = coverY,
    coverScale = coverScale
)

private fun strings(json: String): List<String> {
    val array = JSONArray(json)
    return List(array.length()) { array.getString(it) }
}
