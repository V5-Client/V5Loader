package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.message.TextComponent
import com.chattriggers.ctjs.internal.mixins.BookViewScreenAccessor
import com.chattriggers.ctjs.internal.utils.asMixin
import net.minecraft.client.gui.screens.inventory.BookViewScreen

class Book {
    private var screen: BookViewScreen? = null
    private val customContents = BookViewScreen.BookAccess(emptyList())

    /**
     * Add a page to the book.
     *
     * @param contents the entire message for what the page should be
     * @return the current book to allow method chaining
     */
    fun addPage(contents: TextComponent) = apply {
        customContents.pages.add(contents)
    }

    /**
     * Overloaded method for adding a simple page to the book.
     *
     * @param message a simple string to make the page
     * @return the current book to allow method chaining
     */
    fun addPage(message: String) = apply {
        addPage(TextComponent(message))
    }

    /**
     * Inserts a page at the specified index of the book
     *
     * @param pageIndex the index of the page to set
     * @param message the message to set the page to
     * @return the current book to allow method chaining
     */
    fun insertPage(pageIndex: Int, message: TextComponent) = apply {
        require(pageIndex in customContents.pages.indices) {
            "Invalid index $pageIndex for Book with ${customContents.pageCount} pages"
        }

        customContents.pages.add(pageIndex, message)
        screen?.asMixin<BookViewScreenAccessor>()?.invokeUpdateButtonVisibility()
    }

    fun insertPage(pageIndex: Int, message: String) = insertPage(pageIndex, TextComponent(message))

    /**
     * Sets a page of the book to the specified message.
     *
     * @param pageIndex the index of the page to set
     * @param message the message to set the page to
     * @return the current book to allow method chaining
     */
    fun setPage(pageIndex: Int, message: TextComponent) = apply {
        require(pageIndex in customContents.pages.indices) {
            "Invalid index $pageIndex for Book with ${customContents.pageCount} pages"
        }

        customContents.pages[pageIndex] = message
        screen?.asMixin<BookViewScreenAccessor>()?.invokeUpdateButtonVisibility()
    }

    fun setPage(pageIndex: Int, message: String) = setPage(pageIndex, TextComponent(message))

    @JvmOverloads
    fun display(pageIndex: Int = 0) {
        val newScreen = BookViewScreen(customContents)
        screen = newScreen
        Client.scheduleTask {
            Client.getMinecraft().gui.setScreen(newScreen)
            newScreen.setPage(pageIndex)
        }
    }

    fun isOpen(): Boolean = Client.currentGui.get() === screen

    fun getCurrentPage(): Int =
        screen?.takeIf { Client.currentGui.get() === it }?.asMixin<BookViewScreenAccessor>()?.currentPage ?: -1
}
