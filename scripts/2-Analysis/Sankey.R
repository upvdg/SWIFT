# sankey_organoids.R
# Interactive Sankey plots for organoid state transitions (d4 -> d5 -> d6)
# - d6: "*Ignore" or empty -> "Unclassified"
# - Allowed transitions only (between consecutive days)
# - Link colors = class at d4
# - Exports HTML widgets and vector (PDF) images

# ---------- Packages ----------
pkgs <- c("dplyr", "tidyr", "networkD3", "htmlwidgets", "readr", "webshot")
to_install <- setdiff(pkgs, rownames(installed.packages()))
if (length(to_install) > 0) install.packages(to_install, dependencies = TRUE)
invisible(lapply(pkgs, library, character.only = TRUE))

# Make sure webshot is able to export (requires PhantomJS)
if (!webshot::is_phantomjs_installed()) webshot::install_phantomjs()

# ---------- INPUT FILE ----------
# >>>>>>>>>> MODIFIE ICI <<<<<<<<<<
input_file <- "class_WENR.csv"  # <- chemin vers ton fichier TSV/CSV (3 colonnes : d4, d5, d6)
df <- readr::read_delim(input_file, delim = ";", col_names = c("d4","d5","d6"))

# ---------- Helpers ----------
normalize_d6 <- function(x) {
  x <- ifelse(is.na(x) | trimws(x) == "" | x == "*Ignore", "Unclassified", x)
  x
}

title_case <- function(x) {
  x <- as.character(x)
  paste0(toupper(substr(x, 1, 1)), tolower(substr(x, 2, nchar(x))))
}

allowed_map <- list(
  "Cystic"  = c("Round","Cystic", "Dying", "Budding", "Unclassified"),
  "Round"   = c("Round","Cystic", "Dying", "Budding", "Unclassified"),
  "Budding" = c("Round","Cystic", "Dying", "Budding", "Unclassified")
)

is_allowed <- function(src, dst) {
  src <- as.character(src); dst <- as.character(dst)
  if (!src %in% names(allowed_map)) return(FALSE)
  dst %in% allowed_map[[src]]
}

d4_colors <- c(
  "Cystic"  = "#669933",
  "Round"   = "#3399CC",
  "Budding" = "#CC3366"
)

# ---------- Sankey Plot Function ----------
make_sankey <- function(df, file_html,
                        filter_d4 = NULL,
                        title = "Sankey (filtered; link color = class at d4)",
                        width = NULL, height = NULL) {
  
  stopifnot(all(c("d4", "d5", "d6") %in% names(df)))
  df <- df[, c("d4", "d5", "d6")]
  
  df <- df |>
    mutate(
      d4 = title_case(d4),
      d5 = title_case(d5),
      d6 = normalize_d6(d6),
      d6 = title_case(d6)
    )
  
  if (!is.null(filter_d4)) {
    df <- df |> filter(d4 %in% filter_d4)
    if (nrow(df) == 0) stop("Aucune ligne ne correspond à d4 = ", paste(filter_d4, collapse = ", "))
  }
  
  days <- c("d4", "d5", "d6")
  # Fixed preferred order
  preferred_order <- c("Cystic", "Round", "Budding", "Dying", "Unclassified")
  all_states <- unique(unlist(df[days]))
  ordered_states <- intersect(preferred_order, all_states)
  
  nodes <- expand.grid(day = days, state = ordered_states, KEEP.OUT.ATTRS = FALSE) |>
    mutate(
      name = paste(day, state, sep = ": "),
      label = state
    ) |>
    select(name, label)
  
  id_of <- function(day, state_vec) {
    full_names <- paste(day, state_vec, sep = ": ")
    ids <- match(full_names, nodes$name) - 1  # 0-based
    if (any(is.na(ids))) {
      stop("No node for: ", paste(full_names[is.na(ids)], collapse = ", "))
    }
    return(ids)
  }
  
  flows1 <- df |>
    count(d4, d5, name = "value") |>
    rowwise() |>
    filter(is_allowed(d4, d5)) |>
    ungroup() |>
    mutate(
      source = id_of("d4", d4),
      target = id_of("d5", d5),
      group  = d4
    ) |>
    select(source, target, value, group)
  
  flows2 <- df |>
    count(d4, d5, d6, name = "value") |>
    rowwise() |>
    filter(is_allowed(d5, d6)) |>
    ungroup() |>
    mutate(
      source = id_of("d5", d5),
      target = id_of("d6", d6),
      group  = d4
    ) |>
    select(source, target, value, group)
  
  links <- bind_rows(flows1, flows2)
  
  colourScale <- htmlwidgets::JS(
    sprintf(
      "d3.scaleOrdinal().domain(%s).range(%s)",
      jsonlite::toJSON(names(d4_colors), auto_unbox = TRUE),
      jsonlite::toJSON(unname(d4_colors), auto_unbox = TRUE)
    )
  )
  
  # HTML version with labels
  p_html <- networkD3::sankeyNetwork(
    Links = links,
    Nodes = nodes,
    Source = "source",
    Target = "target",
    Value  = "value",
    NodeID = "label",
    LinkGroup = "group",
    fontSize = 12,
    nodeWidth = 25,
    nodePadding = 10,
    sinksRight = TRUE,
    colourScale = colourScale,
    width = width,
    height = height
  )
  
  htmlwidgets::saveWidget(p_html, file = file_html, selfcontained = TRUE)
  message("Saved: ", normalizePath(file_html))
  
  # PDF version without labels
  nodes_nolabel <- nodes
  nodes_nolabel$label <- ""
  
  p_pdf <- networkD3::sankeyNetwork(
    Links = links,
    Nodes = nodes_nolabel,
    Source = "source",
    Target = "target",
    Value  = "value",
    NodeID = "label",
    LinkGroup = "group",
    fontSize = 0,
    nodeWidth = 25,
    nodePadding = 10,
    sinksRight = TRUE,
    colourScale = colourScale,
    width = width,
    height = height
  )
  
  file_html_tmp <- tempfile(fileext = ".html")
  htmlwidgets::saveWidget(p_pdf, file = file_html_tmp, selfcontained = TRUE)
  
  file_pdf <- sub("\\.html$", ".pdf", file_html)
  webshot::webshot(file_html_tmp, file_pdf, vwidth = 1200, vheight = 800, zoom = 2)
  unlink(file_html_tmp)
  message("PDF exported to: ", normalizePath(file_pdf))
  
  invisible(p_html)
}

# ---------- EXAMPLES ----------
make_sankey(df,
            file_html = "sankey_all_filtered.html",
            title = "Transitions d4 → d5 → d6 (Unclassified = *Ignore + vides)")

for (cls in c("Budding","Cystic","Round")) {
  make_sankey(df,
              file_html = paste0("sankey_from_d4_", tolower(cls), ".html"),
              filter_d4 = cls,
              title = paste0("Transitions depuis d4 = ", cls))
}
