################################################################################
# SWIFT - Single-organoid tracking across consecutive imaging days
#
# Links individual organoids across consecutive timepoints using a
# centroid-proximity, greedy one-to-one assignment, then exports per-organoid
# trajectories (one Excel sheet per measurement) and a per-well summary.
#
# INPUT : one CSV exported from QuPath (Measure > Export measurements,
#         type "Detections", all images/days in a single file).
#         Required columns: Image, Object ID, Classification,
#         Centroid X µm, Centroid Y µm, and the measurement columns
#         listed in MEASUREMENTS below.
#
# IMAGE NAMING CONVENTION (parsed from the "Image" column), e.g. :
#   EDF_sigma-56_20250630_day4_12_MediumB_003.ome.tiff
#   <prefix>_<...>_day<D>_<SampleID>_<Condition tokens>_<Replicate>.ome.tiff
#     - day<D>       : timepoint            -> "day4", "day5", ...
#     - <SampleID>   : number after day<D>_ -> e.g. mouse or donor ID
#     - <Condition>  : up to 5 "_"-separated tokens -> e.g. medium, treatment
#     - <Replicate>  : 3 digits before ".ome"
#   If your naming differs, adapt the regular expressions in section 2.
#
# Repository : https://github.com/upvdg/SWIFT
# License    : BSD-3
################################################################################

library(readr)
library(dplyr)
library(stringr)
library(tidyr)
library(openxlsx)
library(purrr)

# ==============================================================================
# 0. USER PARAMETERS  -----  EDIT THIS SECTION  --------------------------------
# ==============================================================================

## --- Input / output ----------------------------------------------------------
INPUT_CSV   <- "measurements.csv"     # QuPath detection export (all days)
CSV_DELIM   <- ";"                     # delimiter of the exported CSV (";" or ",")
OUTPUT_XLSX <- "SWIFT_tracking_results.xlsx"

## --- Experiment --------------------------------------------------------------
DAYS <- c(4, 5, 6)                     # consecutive timepoints to track, in order
                                       # (2 or more days, e.g. c(1,2,3,4,5))

## --- Tracking ----------------------------------------------------------------
MAX_DISTANCE_UM <- 150                 # max centroid displacement (µm) between
                                       # consecutive days to accept a link.
                                       # Calibrate on your own data: it should
                                       # cover true movement + plate
                                       # repositioning offsets (150 µm was used
                                       # in the SWIFT paper).

## --- Image-name parsing ------------------------------------------------------
IMAGE_PREFIX_REGEX <- "^EDF_sigma-\\d+_"   # prefix removed before parsing
                                           # (e.g. Fiji EDF prefix); set to ""
                                           # if none.

## --- Measurement columns (QuPath names) --------------------------------------
MEASUREMENTS <- c("Area µm^2", "Length µm", "Circularity", "Solidity",
                  "Max diameter µm", "Min diameter µm",
                  "ROI: 2.00 µm per pixel: Channel_0: Mean",
                  "ROI: 2.00 µm per pixel: Channel_0: Std.dev.",
                  "ROI: 2.00 µm per pixel: Channel_0: Min",
                  "ROI: 2.00 µm per pixel: Channel_0: Max",
                  "ROI: 2.00 µm per pixel: Channel_0: Median")

## --- Summary options ---------------------------------------------------------
EXCLUDE_CLASSES_FROM_MEANS <- c("Dying")   # classes excluded from the mean
                                           # measurements in the summary sheet
                                           # (counts/% still include them).
                                           # Use c() to exclude none.

# ==============================================================================
# 1. Load data -----------------------------------------------------------------
# ==============================================================================

df <- read_delim(INPUT_CSV, delim = CSV_DELIM, show_col_types = FALSE)

# ==============================================================================
# 2. Parse metadata from image names -------------------------------------------
#    Adapt the regular expressions below to your own naming convention.
# ==============================================================================

df <- df %>%
  mutate(
    # Timepoint: "day" followed by a number (e.g. day4)
    Day = str_extract(Image, "day\\d+") %>% str_remove("day") %>% as.numeric(),
    # Image identity shared across days (day number blanked out):
    # objects are matched only within the same Image_Base (i.e. same well)
    Image_Base = str_replace(Image, "day\\d+", "day"),
    # Remove acquisition/processing prefix before parsing metadata
    Image_Metadata = str_remove(Image_Base, IMAGE_PREFIX_REGEX),
    # Well ID: a letter followed by digits between underscores (e.g. _B3_)
    Well = str_extract(Image_Metadata, "(?<=_)\\w\\d+(?=_)"),
    # Sample ID (e.g. mouse/donor number): number right after "day_"
    Sample_ID = str_match(Image_Metadata, "day_(\\d+)_")[, 2] %>% as.numeric(),
    # Condition block: tokens between the Sample ID and the 3-digit replicate
    Condition_Block = str_match(Image_Metadata,
                                "day_\\d+_((?:[^_]+_)+)\\d{3}")[, 2] %>%
      str_remove("_$")
  ) %>%
  separate(Condition_Block, into = paste0("Condition_", 1:5),
           sep = "_", fill = "right") %>%
  mutate(
    # Replicate: 3 digits just before ".ome"
    Replicate = str_extract(Image_Metadata, "(\\d{3})(?=\\.ome)") %>% as.numeric()
  ) %>%
  rename(
    Object_ID = `Object ID`,
    Class     = Classification,
    X         = `Centroid X µm`,
    Y         = `Centroid Y µm`
  ) %>%
  filter(Day %in% DAYS)

if (nrow(df) == 0)
  stop("No rows left after parsing/filtering. Check DAYS and the image-name regular expressions (section 2).")

# ==============================================================================
# 3. Link organoids between each pair of consecutive days ----------------------
#    Greedy one-to-one nearest-neighbor assignment, gated at MAX_DISTANCE_UM.
# ==============================================================================

link_days <- function(df, day_from, day_to, max_dist) {
  candidates <- df %>%
    filter(Day %in% c(day_from, day_to)) %>%
    split(.$Image_Base) %>%
    map_dfr(function(img_data) {
      d_from <- img_data %>% filter(Day == day_from)
      d_to   <- img_data %>% filter(Day == day_to)
      if (nrow(d_from) == 0 || nrow(d_to) == 0) return(NULL)
      # All candidate pairs within the same image/well
      expand.grid(from_id = d_from$Object_ID, to_id = d_to$Object_ID,
                  stringsAsFactors = FALSE) %>%
        left_join(d_from %>% select(Object_ID, X, Y),
                  by = c("from_id" = "Object_ID")) %>%
        rename(X_from = X, Y_from = Y) %>%
        left_join(d_to %>% select(Object_ID, X, Y),
                  by = c("to_id" = "Object_ID")) %>%
        rename(X_to = X, Y_to = Y) %>%
        mutate(
          Distance   = sqrt((X_to - X_from)^2 + (Y_to - Y_from)^2),
          Image_Base = unique(img_data$Image_Base)
        ) %>%
        filter(Distance <= max_dist)
    })

  if (nrow(candidates) == 0) {
    warning(sprintf("No links found between day %s and day %s.", day_from, day_to))
    return(tibble(Image_Base = character(),
                  !!paste0("Day", day_from, "_ID") := character(),
                  !!paste0("Day", day_to,   "_ID") := character(),
                  !!paste0("Dist_", day_from, "_", day_to) := numeric()))
  }

  # Greedy one-to-one: shortest distances first, each object linked at most once
  candidates %>%
    arrange(Image_Base, Distance) %>%
    group_by(Image_Base) %>%
    filter(!duplicated(from_id) & !duplicated(to_id)) %>%
    ungroup() %>%
    select(Image_Base, from_id, to_id, Distance) %>%
    rename(!!paste0("Day", day_from, "_ID") := from_id,
           !!paste0("Day", day_to,   "_ID") := to_id,
           !!paste0("Dist_", day_from, "_", day_to) := Distance)
}

# Build links for every consecutive day pair, then chain them into tracks.
# Tracks start at the first day; they terminate early (NA) if no valid
# continuation exists (organoid disappeared or moved beyond the threshold).
day_pairs <- map2(DAYS[-length(DAYS)], DAYS[-1], c)

linked_objects <- reduce(day_pairs, .init = NULL, function(acc, pair) {
  links <- link_days(df, pair[1], pair[2], MAX_DISTANCE_UM)
  if (is.null(acc)) return(links)
  left_join(acc, links, by = c("Image_Base", paste0("Day", pair[1], "_ID")))
})

# ==============================================================================
# 4. Merge per-day measurements and classes back into each trajectory ----------
# ==============================================================================

meta_day <- function(day) {
  df %>%
    filter(Day == day) %>%
    rename_with(~paste0("Day", day, "_", .), .cols = -Object_ID) %>%
    rename(!!paste0("Day", day, "_Object_ID") := Object_ID)
}

final_result <- reduce(DAYS, .init = linked_objects, function(acc, day) {
  left_join(acc, meta_day(day),
            by = setNames(c("Day%s_Image_Base", "Day%s_Object_ID") %>%
                            sprintf(day),
                          c("Image_Base", sprintf("Day%s_ID", day))))
})

# ==============================================================================
# 5. Attach clean per-image metadata --------------------------------------------
# ==============================================================================

condition_cols <- df %>% select(starts_with("Condition_")) %>% names()

meta_cols <- df %>%
  select(Image_Base, Well, Sample_ID, all_of(condition_cols), Replicate) %>%
  distinct()

final_df <- final_result %>%
  left_join(meta_cols, by = "Image_Base")

# Drop empty Condition_* columns (naming convention allows up to 5 tokens)
non_empty_conditions <- final_df %>%
  select(all_of(condition_cols)) %>%
  summarise(across(everything(), ~ any(!is.na(.) & . != ""))) %>%
  select(where(~ .x)) %>%
  names()

# ==============================================================================
# 6. Excel export: one sheet per measurement -----------------------------------
# ==============================================================================

wb <- createWorkbook()

coord_cols <- as.vector(outer(paste0("Day", DAYS, "_"), c("X", "Y"), paste0))
class_cols <- paste0("Day", DAYS, "_Class")
dist_cols  <- grep("^Dist_", names(final_df), value = TRUE)  # link distances,
                                                             # useful to calibrate
                                                             # MAX_DISTANCE_UM

for (measure in MEASUREMENTS) {
  cols <- colnames(final_df)[
    Reduce(`|`, lapply(DAYS, function(d)
      startsWith(colnames(final_df), paste0("Day", d, "_", measure))))
  ]

  if (length(cols) > 0) {
    sheet_df <- final_df %>%
      select(Image_Base, Well, Sample_ID, all_of(non_empty_conditions),
             Replicate, any_of(coord_cols), any_of(class_cols),
             any_of(dist_cols), all_of(cols))

    # Excel sheet names: <=31 chars, no special characters, unique
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

# ==============================================================================
# 7. Summary sheet: counts, % per class, and mean measurements per well/day ----
# ==============================================================================

summary_all <- list()
for (day in paste0("Day", DAYS)) {
  day_cols     <- grep(paste0("^", day, "_"), names(final_df), value = TRUE)
  measures_day <- day_cols[day_cols %in% paste0(day, "_", MEASUREMENTS)]
  class_col    <- paste0(day, "_Class")

  temp <- final_df %>%
    select(Image_Base, Well, Sample_ID, all_of(non_empty_conditions),
           Replicate, !!class_col, all_of(measures_day)) %>%
    mutate(Day = day)

  names(temp) <- names(temp) %>% str_replace(paste0("^", day, "_"), "")
  names(temp)[names(temp) == class_col] <- "Class"

  summary_all[[day]] <- temp
}

summary_df <- bind_rows(summary_all)

group_vars <- c("Image_Base", "Well", "Sample_ID", non_empty_conditions,
                "Replicate", "Day")

summary_counts_n <- summary_df %>%
  count(across(all_of(c(group_vars, "Class")))) %>%
  pivot_wider(names_from = Class, values_from = n,
              values_fill = 0, names_prefix = "n of ")

summary_counts_pct <- summary_df %>%
  count(across(all_of(c(group_vars, "Class")))) %>%
  group_by(across(all_of(group_vars))) %>%
  mutate(Total = sum(n, na.rm = TRUE)) %>%
  ungroup() %>%
  mutate(Percent = round(n / Total * 100, 2)) %>%
  select(-n, -Total) %>%
  pivot_wider(names_from = Class, values_from = Percent,
              values_fill = 0, names_prefix = "% of ")

summary_means <- summary_df %>%
  filter(!is.na(Class) & Class != "" &
           !(Class %in% EXCLUDE_CLASSES_FROM_MEANS)) %>%
  group_by(across(all_of(group_vars))) %>%
  summarise(across(any_of(MEASUREMENTS), ~ mean(.x, na.rm = TRUE),
                   .names = "Mean_{.col}"), .groups = "drop")

summary_stats <- summary_means %>%
  left_join(summary_counts_pct, by = group_vars) %>%
  left_join(summary_counts_n,   by = group_vars)

addWorksheet(wb, sheetName = "Summary")
writeData(wb, sheet = "Summary", summary_stats)

saveWorkbook(wb, OUTPUT_XLSX, overwrite = TRUE)

message("Done. Tracked ", nrow(final_df), " trajectories across days ",
        paste(DAYS, collapse = " -> "), ". Output: ", OUTPUT_XLSX)
