/*
 * Copyright 2018 John Grosh (jagrosh).
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
package com.jagrosh.jmusicbot.playlist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlaylistLoader
{
    private final BotConfig config;
    private final ObjectMapper objectMapper;

    public PlaylistLoader(BotConfig config)
    {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> getPlaylistNames()
    {
        if(folderExists())
        {
            File folder = new File(OtherUtil.getPath(config.getPlaylistsFolder()).toString());
            return Arrays.stream(Objects.requireNonNull(folder.listFiles((pathname) -> pathname.getName().endsWith(".json")))).map(f -> f.getName().substring(0,f.getName().length()-5)).collect(Collectors.toList());
        }
        else
        {
            createFolder();
            return Collections.emptyList();
        }
    }

    public void createFolder()
    {
        try
        {
            Files.createDirectory(OtherUtil.getPath(config.getPlaylistsFolder()));
        }
        catch (IOException ignore) {}
    }

    public boolean folderExists()
    {
        return Files.exists(OtherUtil.getPath(config.getPlaylistsFolder()));
    }

    public void createPlaylist(String name, String ownerId, String guildId) throws IOException
    {
        ObjectNode playlist = objectMapper.createObjectNode();
        playlist.put("name", name);
        playlist.put("authorId", ownerId);
        playlist.put("guildId", guildId);
        playlist.put("shuffle", false);
        playlist.putArray("tracks");
        objectMapper.writeValue(new File(OtherUtil.getPath(config.getPlaylistsFolder()+File.separator+name+".json").toString()), playlist);
    }

    public void deletePlaylist(String name) throws IOException
    {
        Files.delete(OtherUtil.getPath(config.getPlaylistsFolder()+File.separator+name+".json"));
    }

    public void writePlaylist(String name, String[] tracks) throws IOException
    {
        ObjectNode playlistNode = (ObjectNode) objectMapper.readTree(new File(config.getPlaylistsFolder()+File.separator+name+".json"));
        for (String track : tracks)
        {
            ((ArrayNode) playlistNode.get("tracks")).add(track);
        }
        objectMapper.writeValue(new File(OtherUtil.getPath(config.getPlaylistsFolder()+File.separator+name+".json").toString()), playlistNode);
    }

    public Playlist getPlaylist(String name)
    {
        if(!getPlaylistNames().contains(name))
            return null;
        try
        {
            if(folderExists())
            {
                JsonNode playlistNode = objectMapper.readTree(new File(config.getPlaylistsFolder()+File.separator+name+".json"));

                String tracksNode = playlistNode.get("tracks").toString();
                List<String> list = Arrays.asList(objectMapper.readValue(tracksNode, String[].class));

                boolean shuffle = playlistNode.get("shuffle").asBoolean();
                if (shuffle) shuffle(list);

                String authorId = playlistNode.get("authorId").toString();
                String guildId = playlistNode.get("guildId").toString();

                return new Playlist(name, list, shuffle, authorId, guildId);
            }
            else
            {
                createFolder();
                return null;
            }
        }
        catch(IOException e)
        {
            return null;
        }
    }


    private static <T> void shuffle(List<T> list)
    {
        for(int first =0; first<list.size(); first++)
        {
            int second = (int)(Math.random()*list.size());
            T tmp = list.get(first);
            list.set(first, list.get(second));
            list.set(second, tmp);
        }
    }


    public int getUserPlaylistCount(String userId)
    {
        final int[] count = {0};
        try {
            this.getPlaylistNames().forEach((name) -> {
                try {
                    JsonNode playlistNode = objectMapper.readTree(new File(config.getPlaylistsFolder() + File.separator + name + ".json"));
                    String playlistAuthorId = playlistNode.get("authorId").toString();
                    if (Objects.equals(playlistAuthorId, userId)) count[0]++;
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return count[0];
    }


    public class Playlist
    {
        private final String name;
        private final List<String> items;
        private final boolean shuffle;
        private final List<AudioTrack> tracks = new LinkedList<>();
        private final List<PlaylistLoadError> errors = new LinkedList<>();
        private boolean loaded = false;
        private final String authorId;
        private final String guildId;

        private Playlist(String name, List<String> items, boolean shuffle, String authorId, String guildId)
        {
            this.name = name;
            this.items = items;
            this.shuffle = shuffle;
            this.authorId = authorId;
            this.guildId = guildId;
        }

        public void loadTracks(AudioPlayerManager manager, Consumer<AudioTrack> consumer, Runnable callback)
        {
            if(loaded)
                return;
            loaded = true;
            for(int i=0; i<items.size(); i++)
            {
                boolean last = i+1 == items.size();
                int index = i;
                manager.loadItemOrdered(name, items.get(i), new AudioLoadResultHandler()
                {
                    private void done()
                    {
                        if(last)
                        {
                            if(shuffle)
                                shuffleTracks();
                            if(callback != null)
                                callback.run();
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack at)
                    {
                        if(config.isTooLong(at))
                            errors.add(new PlaylistLoadError(index, items.get(index), "This track is longer than the allowed maximum"));
                        else
                        {
                            at.setUserData(0L);
                            tracks.add(at);
                            consumer.accept(at);
                        }
                        done();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist ap)
                    {
                        if(ap.isSearchResult())
                        {
                            trackLoaded(ap.getTracks().get(0));
                        }
                        else if(ap.getSelectedTrack()!=null)
                        {
                            trackLoaded(ap.getSelectedTrack());
                        }
                        else
                        {
                            List<AudioTrack> loaded = new ArrayList<>(ap.getTracks());
                            if(shuffle)
                                for(int first =0; first<loaded.size(); first++)
                                {
                                    int second = (int)(Math.random()*loaded.size());
                                    AudioTrack tmp = loaded.get(first);
                                    loaded.set(first, loaded.get(second));
                                    loaded.set(second, tmp);
                                }
                            loaded.removeIf(track -> config.isTooLong(track));
                            loaded.forEach(at -> at.setUserData(0L));
                            tracks.addAll(loaded);
                            loaded.forEach(at -> consumer.accept(at));
                        }
                        done();
                    }

                    @Override
                    public void noMatches()
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "No matches found."));
                        done();
                    }

                    @Override
                    public void loadFailed(FriendlyException fe)
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "Failed to load track: "+fe.getLocalizedMessage()));
                        done();
                    }
                });
            }
        }

        public void shuffleTracks()
        {
            shuffle(tracks);
        }

        public String getName()
        {
            return name;
        }

        public List<String> getItems()
        {
            return items;
        }

        public List<AudioTrack> getTracks()
        {
            return tracks;
        }

        public List<PlaylistLoadError> getErrors()
        {
            return errors;
        }

        public String getAuthorId() {
            return authorId;
        }

        public String getGuildId() {
            return guildId;
        }
    }

    public class PlaylistLoadError
    {
        private final int number;
        private final String item;
        private final String reason;

        private PlaylistLoadError(int number, String item, String reason)
        {
            this.number = number;
            this.item = item;
            this.reason = reason;
        }

        public int getIndex()
        {
            return number;
        }

        public String getItem()
        {
            return item;
        }

        public String getReason()
        {
            return reason;
        }
    }
}
