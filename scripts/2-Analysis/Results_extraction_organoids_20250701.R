library(readr)
library(dplyr)
library(stringr)
library(tidyr)
library(openxlsx)
library(purrr)

# 1. Charger les données --------------------------------------------------
df <- read_delim("20250630_Exp13_MeasurementsForFigure_semicolon.csv", delim = ";", show_col_types = FALSE)

# 2. Extraction et nettoyage ----------------------------------------------
df <- df %>%
  mutate(
    Day = str_extract(Image, "day\\d+") %>% str_remove("day") %>% as.numeric(),
    Image_Base = str_replace(Image, "day\\d+", "day"),
    Image_Metadata = str_remove(Image_Base, "^EDF_sigma-56_"),
    Well = str_extract(Image_Metadata, "(?<=_)\\w\\d+(?=_)"),
    Mouse_ID = str_match(Image_Metadata, "day_(\\d+)_")[, 2] %>% as.numeric(),
    Condition_Block = str_match(Image_Metadata, "day_\\d+_((?:[^_]+_)+)\\d{3}")[, 2] %>% str_remove("_$")
  ) %>%
  separate(Condition_Block, into = paste0("Condition_", 1:5), sep = "_", fill = "right") %>%
  mutate(
    Replicate = str_extract(Image_Metadata, "(\\d{3})(?=\\.ome)") %>% as.numeric()
  ) %>%
  rename(
    Object_ID = `Object ID`,
    Class = Classification,
    X = `Centroid X µm`,
    Y = `Centroid Y µm`
  ) %>%
  filter(Day %in% c(4, 5, 6))

# 3. Appariements ---------------------------------------------------------

# Day4 → Day5
candidates_45 <- df %>%
  filter(Day %in% c(4, 5)) %>%
  split(.$Image_Base) %>%
  map_dfr(function(img_data) {
    df4 <- img_data %>% filter(Day == 4)
    df5 <- img_data %>% filter(Day == 5)
    if (nrow(df4) == 0 || nrow(df5) == 0) return(NULL)
    expand.grid(df4_id = df4$Object_ID, df5_id = df5$Object_ID) %>%
      left_join(df4 %>% select(Object_ID, X, Y), by = c("df4_id" = "Object_ID")) %>%
      rename(X4 = X, Y4 = Y) %>%
      left_join(df5 %>% select(Object_ID, X, Y), by = c("df5_id" = "Object_ID")) %>%
      rename(X5 = X, Y5 = Y) %>%
      mutate(
        Distance = sqrt((X5 - X4)^2 + (Y5 - Y4)^2),
        Image_Base = unique(img_data$Image_Base)
      ) %>%
      filter(Distance <= 200)
  })

best_links_45 <- candidates_45 %>%
  arrange(Image_Base, Distance) %>%
  group_by(Image_Base) %>%
  filter(!duplicated(df4_id) & !duplicated(df5_id)) %>%
  ungroup() %>%
  rename(Day4_ID = df4_id, Day5_ID = df5_id, Dist_45 = Distance)

# Day5 → Day6
candidates_56 <- df %>%
  filter(Day %in% c(5, 6)) %>%
  split(.$Image_Base) %>%
  map_dfr(function(img_data) {
    df5 <- img_data %>% filter(Day == 5)
    df6 <- img_data %>% filter(Day == 6)
    if (nrow(df5) == 0 || nrow(df6) == 0) return(NULL)
    expand.grid(df5_id = df5$Object_ID, df6_id = df6$Object_ID) %>%
      left_join(df5 %>% select(Object_ID, X, Y), by = c("df5_id" = "Object_ID")) %>%
      rename(X5 = X, Y5 = Y) %>%
      left_join(df6 %>% select(Object_ID, X, Y), by = c("df6_id" = "Object_ID")) %>%
      rename(X6 = X, Y6 = Y) %>%
      mutate(
        Distance = sqrt((X6 - X5)^2 + (Y6 - Y5)^2),
        Image_Base = unique(img_data$Image_Base)
      ) %>%
      filter(Distance <= 200)
  })

best_links_56 <- candidates_56 %>%
  arrange(Image_Base, Distance) %>%
  group_by(Image_Base) %>%
  filter(!duplicated(df5_id) & !duplicated(df6_id)) %>%
  ungroup() %>%
  rename(Day5_ID = df5_id, Day6_ID = df6_id, Dist_56 = Distance)

# Fusion
linked_objects <- best_links_45 %>%
  left_join(best_links_56, by = c("Image_Base", "Day5_ID")) %>%
  mutate(
    Day6_ID = coalesce(Day6_ID, NA_character_),
    Dist_56 = coalesce(Dist_56, NA_real_)
  )

# 4. Récupération des métadonnées -----------------------------------------
meta_day <- function(day) {
  df %>%
    filter(Day == day) %>%
    rename_with(~paste0("Day", day, "_", .), .cols = -Object_ID) %>%
    rename(!!paste0("Day", day, "_Object_ID") := Object_ID)
}

df4_meta <- meta_day(4)
df5_meta <- meta_day(5)
df6_meta <- meta_day(6)

final_result <- linked_objects %>%
  left_join(df4_meta, by = c("Image_Base" = "Day4_Image_Base", "Day4_ID" = "Day4_Object_ID")) %>%
  left_join(df5_meta, by = c("Image_Base" = "Day5_Image_Base", "Day5_ID" = "Day5_Object_ID")) %>%
  left_join(df6_meta, by = c("Image_Base" = "Day6_Image_Base", "Day6_ID" = "Day6_Object_ID"))

# 5. Métadonnées globales propres -----------------------------------------
condition_cols <- df %>% select(starts_with("Condition_")) %>% names()
meta_cols <- df %>%
  select(Image_Base, Well, Mouse_ID, all_of(condition_cols), Replicate) %>%
  distinct()

final_df <- final_result %>%
  left_join(meta_cols, by = "Image_Base")

# Supprimer colonnes Condition_* vides
non_empty_conditions <- final_df %>%
  select(all_of(condition_cols)) %>%
  summarise(across(everything(), ~ any(!is.na(.) & . != ""))) %>%
  select(where(~ .x)) %>%
  names()

# 6. Fichier Excel --------------------------------------------------------
wb <- createWorkbook()

measures <- c("Area µm^2", "Length µm", "Circularity", "Solidity",
              "Max diameter µm", "Min diameter µm",
              "ROI: 2.00 µm per pixel: Channel_0: Mean",
              "ROI: 2.00 µm per pixel: Channel_0: Std.dev.",
              "ROI: 2.00 µm per pixel: Channel_0: Min",
              "ROI: 2.00 µm per pixel: Channel_0: Max",
              "ROI: 2.00 µm per pixel: Channel_0: Median")

for (measure in measures) {
  cols <- colnames(final_df)[startsWith(colnames(final_df), paste0("Day4_", measure)) |
                               startsWith(colnames(final_df), paste0("Day5_", measure)) |
                               startsWith(colnames(final_df), paste0("Day6_", measure))]
  
  if (length(cols) > 0) {
    coord_cols <- c("Day4_X", "Day4_Y", "Day5_X", "Day5_Y", "Day6_X", "Day6_Y")
    class_cols <- c("Day4_Class", "Day5_Class", "Day6_Class")
    
    sheet_df <- final_df %>%
      select(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate,
             all_of(coord_cols), all_of(class_cols), all_of(cols))
    
    sheet_base <- str_replace_all(measure, "[^A-Za-z0-9]", "_") %>% substr(1, 25)
    suffix <- 1
    sheet_name <- sheet_base
    while (sheet_name %in% names(wb)) {
      suffix <- suffix + 1
      sheet_name <- paste0(sheet_base, "_", suffix)
    }
    addWorksheet(wb, sheetName = sheet_name)
    writeData(wb, sheet = sheet_name, sheet_df)
  }
}

# 7. Résumé statistique ---------------------------------------------------
summary_all <- list()
for (day in c("Day4", "Day5", "Day6")) {
  day_cols <- grep(paste0("^", day, "_"), names(final_df), value = TRUE)
  measures_day <- day_cols[day_cols %in% paste0(day, "_", measures)]
  class_col <- paste0(day, "_Class")
  
  temp <- final_df %>%
    select(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate, !!class_col, all_of(measures_day)) %>%
    mutate(Day = day)
  
  names(temp) <- names(temp) %>% str_replace(paste0("^", day, "_"), "")
  names(temp)[names(temp) == class_col] <- "Class"
  
  summary_all[[day]] <- temp
}

summary_df <- bind_rows(summary_all)

summary_counts_n <- summary_df %>%
  count(across(c(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate, Day, Class))) %>%
  pivot_wider(names_from = Class, values_from = n, values_fill = 0, names_prefix = "n of ")

summary_counts_pct <- summary_df %>%
  count(across(c(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate, Day, Class))) %>%
  group_by(across(c(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate, Day))) %>%
  mutate(Total = sum(n, na.rm = TRUE)) %>%
  ungroup() %>%
  mutate(Percent = round(n / Total * 100, 2)) %>%
  select(-n, -Total) %>%
  pivot_wider(names_from = Class, values_from = Percent, values_fill = 0, names_prefix = "% of ")

summary_means <- summary_df %>%
  filter(!is.na(Class) & Class != "" & Class != "Dying") %>%
  group_by(across(c(Image_Base, Well, Mouse_ID, all_of(non_empty_conditions), Replicate, Day))) %>%
  summarise(across(all_of(measures), ~ mean(.x, na.rm = TRUE), .names = "Mean_{.col}"), .groups = "drop")

summary_stats <- summary_means %>%
  left_join(summary_counts_pct, by = c("Image_Base", "Well", "Mouse_ID", non_empty_conditions, "Replicate", "Day")) %>%
  left_join(summary_counts_n, by = c("Image_Base", "Well", "Mouse_ID", non_empty_conditions, "Replicate", "Day"))

addWorksheet(wb, sheetName = "Résumé")
writeData(wb, sheet = "Résumé", summary_stats)

saveWorkbook(wb, "Suivi_complet_mesures_par_feuille.xlsx", overwrite = TRUE)
