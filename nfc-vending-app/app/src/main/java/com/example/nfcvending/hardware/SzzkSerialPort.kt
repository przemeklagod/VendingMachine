package com.example.nfcvending.hardware

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class SzzkSerialPort(file: File, baudRate: Int) {
    private var fd: FileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null

    init {
        fd = open(file.absolutePath, baudRate, 0)
        val realFd = fd ?: throw IllegalStateException("Cannot open serial port: ${file.absolutePath}")
        input = FileInputStream(realFd)
        output = FileOutputStream(realFd)
    }

    fun inputStream(): InputStream = input ?: throw IllegalStateException("Input stream closed")

    fun outputStream(): OutputStream = output ?: throw IllegalStateException("Output stream closed")

    private external fun open(path: String, baudRate: Int, flags: Int): FileDescriptor?

    companion object {
        init {
            try {
                System.loadLibrary("szzkserialport")
            } catch (_: Throwable) {
                // Native library may be provided only on target device image.
            }
        }
    }
}
