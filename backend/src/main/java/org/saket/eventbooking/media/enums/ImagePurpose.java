package org.saket.eventbooking.media.enums;

/** What an uploaded image is for; decides its folder in the bucket. */
public enum ImagePurpose {
    EVENT("events"),
    ARTIST("artists");

    private final String folder;

    ImagePurpose(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
