package com.ghostchu.quickshop.localization.game.game.distributions;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface Distribution {
    @NotNull List<String> getAvailableFiles();

    @NotNull List<String> getAvailableLanguages();

    @NotNull String getFile(String fileCrowdinPath, String crowdinLocale);

    @NotNull String getFile(String fileCrowdinPath, String crowdinLocale, boolean forceFlush);
}
