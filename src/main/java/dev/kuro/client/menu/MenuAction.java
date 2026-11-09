package dev.kuro.client.menu;

import java.util.List;

public enum MenuAction {
   SINGLEPLAYER("Singleplayer"),
   MULTIPLAYER("Multiplayer"),
   OPTIONS("Options"),
   CONFIGS("Configs"),
   QUIT("Quit"),
   LANGUAGE("Language"),
   ACCESSIBILITY("Accessibility"),
   KURO("Kuro Client");

   public static final List<MenuAction> MAIN = List.of(SINGLEPLAYER, MULTIPLAYER, OPTIONS, CONFIGS, QUIT);
   private final String label;

   MenuAction(String label) {
      this.label = label;
   }

   public String label() {
      return this.label;
   }
}
