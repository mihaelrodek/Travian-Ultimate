/**
 * Bot commands — typed units of work that drive the {@link com.travianbot.bot.client.TravianClient}.
 * <p>
 * Modeled after TravBotSharp's {@code MainCore/Commands/} folder (Navigate, Update, Features).
 * Each command is an atomic operation (navigate, update a village's data, send an attack).
 * They compose into higher-level tasks that the scheduler enqueues per account.
 */
package com.travianbot.bot.command;
