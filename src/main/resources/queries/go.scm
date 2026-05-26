;; Go tree-sitter queries for symbol extraction
;; Capture names: @name, @node, @path, @parent, @parameters

;; ============================================================
;; Import specs
;; ============================================================
(import_spec
  path: (interpreted_string_literal) @path
) @node

;; ============================================================
;; Type declarations — structs
;; ============================================================
(type_declaration
  (type_spec
    name: (type_identifier) @name
    type: (struct_type))
) @node

;; ============================================================
;; Type declarations — interfaces
;; ============================================================
(type_declaration
  (type_spec
    name: (type_identifier) @name
    type: (interface_type))
) @node

;; ============================================================
;; Function declarations
;; ============================================================
(function_declaration
  name: (identifier) @name
  parameters: (parameter_list) @parameters
) @node

;; ============================================================
;; Method declarations (with receiver — pointer type)
;; ============================================================
(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: (pointer_type (type_identifier) @parent)))
  name: (field_identifier) @name
  parameters: (parameter_list) @parameters
) @node

;; ============================================================
;; Method declarations (with receiver — value type)
;; ============================================================
(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: (type_identifier) @parent))
  name: (field_identifier) @name
  parameters: (parameter_list) @parameters
) @node
