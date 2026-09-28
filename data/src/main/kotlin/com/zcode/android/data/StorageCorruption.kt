package com.zcode.android.data

/** §33 storage category typed fault — corrupted persisted state is never guessed. */
class StorageCorruption(message: String) : Exception(message)
