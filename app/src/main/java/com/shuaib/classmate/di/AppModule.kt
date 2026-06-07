package com.shuaib.classmate.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.shuaib.classmate.ai.AiCoordinator
import com.shuaib.classmate.ai.GeminiAiProvider
import com.shuaib.classmate.ai.GroqAiProvider
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.data.FirestoreManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideGeminiAiProvider(client: OkHttpClient, gson: Gson): GeminiAiProvider =
        GeminiAiProvider(client, gson)

    @Provides
    @Singleton
    fun provideGroqAiProvider(client: OkHttpClient, gson: Gson): GroqAiProvider =
        GroqAiProvider(client, gson)

    @Provides
    @Singleton
    fun provideAiCoordinator(
        geminiAiProvider: GeminiAiProvider,
        groqAiProvider: GroqAiProvider
    ): AiCoordinator = AiCoordinator(geminiAiProvider, groqAiProvider)

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestoreManager(): FirestoreManager = FirestoreManager

    @Provides
    @Singleton
    fun provideChatRepository(): ChatRepository = ChatRepository
}
