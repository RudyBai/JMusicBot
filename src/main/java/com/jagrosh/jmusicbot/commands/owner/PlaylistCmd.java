/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.commands.owner;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.OwnerCommand;
import com.jagrosh.jmusicbot.commands.PlaylistCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import net.dv8tion.jda.api.entities.User;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlaylistCmd extends PlaylistCommand
{
    private final static int USER_PLAYLIST_LIMIT = 5;

    private final Bot bot;
    public PlaylistCmd(Bot bot)
    {
        this.bot = bot;
        this.guildOnly = false;
        this.name = "playlist";
        this.arguments = "<append|delete|make|setdefault>";
        this.help = "playlist management";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.children = new PlaylistCommand[]{
            new ListCmd(),
            new AppendlistCmd(),
            new DeletelistCmd(),
            new MakelistCmd(),
            new DefaultlistCmd(bot)
        };
    }

    @Override
    public void execute(CommandEvent event) 
    {
        StringBuilder builder = new StringBuilder(event.getClient().getWarning()+" Playlist Management Commands:\n");
        for(Command cmd: this.children)
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" ").append(cmd.getName())
                    .append(" ").append(cmd.getArguments()==null ? "" : cmd.getArguments()).append("` - ").append(cmd.getHelp());
        event.reply(builder.toString());
    }
    
    public class MakelistCmd extends PlaylistCommand
    {
        public MakelistCmd()
        {
            this.name = "make";
            this.aliases = new String[]{"create"};
            this.help = "makes a new playlist";
            this.arguments = "<name>";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event)
        {
            if (userIsNotAllowedToMakeMorePlaylists(event.getAuthor())) {
                event.reply("You have reached the playlist limit.");
                return;
            }

            String playlistName = event.getArgs()
                    .replaceAll("\\s+", "_")
                    .replaceAll("[*?|/\":<>]", "");
            if(playlistName.isEmpty())
            {
                event.replyError("Please provide a name for the playlist!");
                return;
            }

            if (bot.getPlaylistLoader().getPlaylist(playlistName) != null)
            {
                event.reply(event.getClient().getError()+" Playlist `"+playlistName+"` already exists!");
                return;
            }

            try
            {
                bot.getPlaylistLoader().createPlaylist(playlistName, event.getMember().getId(), event.getMember().getGuild().getId());
                event.reply(event.getClient().getSuccess()+" Successfully created playlist `"+playlistName+"`!");
            }
            catch(IOException e)
            {
                event.reply(event.getClient().getError()+" I was unable to create the playlist: "+e.getLocalizedMessage());
            }
        }

        private boolean userIsNotAllowedToMakeMorePlaylists(User user)
        {
            return bot.getPlaylistLoader().getUserPlaylistCount(user.getId()) >= USER_PLAYLIST_LIMIT && bot.getConfig().getOwnerId() != Long.parseLong(user.getId());
        }
    }
    
    public class DeletelistCmd extends PlaylistCommand
    {
        public DeletelistCmd()
        {
            this.name = "delete";
            this.aliases = new String[]{"remove"};
            this.help = "deletes an existing playlist";
            this.arguments = "<name>";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event) 
        {
            String playlistName = event.getArgs().replaceAll("\\s+", "_");
            Playlist playlist = bot.getPlaylistLoader().getPlaylist(playlistName);
            if(bot.getPlaylistLoader().getPlaylist(playlistName)==null)
            {
                event.reply(event.getClient().getError() + " Playlist `" + playlistName + "` doesn't exist!");
                return;
            }

            try
            {
                bot.getPlaylistLoader().deletePlaylist(playlistName);
                event.reply(event.getClient().getSuccess()+" Successfully deleted playlist `"+playlistName+"`!");
            }
            catch(IOException e)
            {
                event.reply(event.getClient().getError()+" I was unable to delete the playlist: "+e.getLocalizedMessage());
            }
        }
    }
    
    public class AppendlistCmd extends PlaylistCommand
    {
        public AppendlistCmd()
        {
            this.name = "append";
            this.aliases = new String[]{"add"};
            this.help = "appends songs to an existing playlist";
            this.arguments = "<name> <URL> | <URL> | ...";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event)
        {
            String[] parts = event.getArgs().split("\\s+", 2);
            if(parts.length<2)
            {
                event.reply(event.getClient().getError()+" Please include a playlist name and URLs to add!");
                return;
            }
            String pname = parts[0];
            Playlist playlist = bot.getPlaylistLoader().getPlaylist(pname);
            if(playlist==null) {
                event.reply(event.getClient().getError() + " Playlist `" + pname + "` doesn't exist!");
                return;
            }

            if (userIsNotAllowedToWriteToPlaylist(event, playlist))
            {
                event.reply("You are not allowed to append new tracks to this playlist!");
                return;
            }

            String[] tracks = parts[1].split("\\|");
            tracks = Arrays.stream(tracks).map(String::trim).toArray(String[]::new);

            try
            {
                bot.getPlaylistLoader().writePlaylist(pname, tracks);
                event.reply(event.getClient().getSuccess()+" Successfully added "+tracks.length+" items to playlist `"+pname+"`!");
            }
            catch(IOException e)
            {
                event.reply(event.getClient().getError()+" I was unable to append to the playlist: "+e.getLocalizedMessage());
            }
        }

        private boolean userIsNotAllowedToWriteToPlaylist(CommandEvent event, Playlist playlist)
        {
            return !(
                    event.getAuthor().getId().equals(playlist.getAuthorId()) ||
                            event.getGuild().getId().equals(playlist.getGuildId()) ||
                            event.getAuthor().getId().equals(Long.toString(bot.getConfig().getOwnerId()))
            );
        }
    }
    
    public class DefaultlistCmd extends AutoplaylistCmd 
    {
        public DefaultlistCmd(Bot bot)
        {
            super(bot);
            this.name = "setdefault";
            this.aliases = new String[]{"default"};
            this.arguments = "<playlistname|NONE>";
            this.guildOnly = true;
        }
    }
    
    public class ListCmd extends PlaylistCommand
    {
        public ListCmd()
        {
            this.name = "all";
            this.aliases = new String[]{"available","list"};
            this.help = "lists all available playlists";
            this.guildOnly = true;
        }

        @Override
        protected void execute(CommandEvent event) {
            if (!bot.getPlaylistLoader().folderExists())
                bot.getPlaylistLoader().createFolder();
            if (!bot.getPlaylistLoader().folderExists()) {
                event.reply(event.getClient().getWarning() + " Playlists folder does not exist and could not be created!");
                return;
            }
            if (!event.getArgs().isEmpty()) {
                Playlist playlist = bot.getPlaylistLoader().getPlaylist(event.getArgs());
                if (playlist == null)
                {
                    event.reply("Playlist does not exist!");
                    return;
                }
                StringBuilder builder = new StringBuilder(event.getClient().getSuccess() + " Tracks on `" + playlist.getName() + "`:\n");
                playlist.getTracks().forEach(track -> builder.append("\n`").append(track.getIdentifier()).append("` "));
                event.reply(builder.toString());
            } else {
                List<String> list = bot.getPlaylistLoader().getPlaylistNames();
                if (list == null)
                    event.reply(event.getClient().getError() + " Failed to load available playlists!");
                else if (list.isEmpty())
                    event.reply(event.getClient().getWarning() + " There are no playlists in the Playlists folder!");
                else {
                    StringBuilder builder = new StringBuilder(event.getClient().getSuccess() + " Available playlists:\n");
                    list.forEach(str -> builder.append("`").append(str).append("` "));
                    event.reply(builder.toString());
                }
            }
        }
    }
}
