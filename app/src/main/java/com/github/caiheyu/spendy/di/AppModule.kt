package com.github.caiheyu.spendy.di

import android.content.Context
import androidx.room.Room
import com.github.caiheyu.spendy.data.SpendyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SpendyDatabase =
        Room.databaseBuilder(context, SpendyDatabase::class.java, "spendy.db").build()
}
