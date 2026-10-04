// Generated from verified RPC signatures by scripts/web/generate-ai-tools.py.
export const tools = [
  {
    "name": "post_notice",
    "cr": true,
    "arguments": [
      {
        "name": "target_batch",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "notice_title",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "notice_body",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "ai_post_cancellation_notice",
    "cr": true,
    "arguments": [
      {
        "name": "target_batch",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "change_date",
        "type": "date",
        "description": "YYYY-MM-DD",
        "optional": false
      },
      {
        "name": "target_silent",
        "type": "boolean",
        "description": "boolean",
        "optional": true
      }
    ],
    "destructive": false
  },
  {
    "name": "edit_notice",
    "cr": true,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_title",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_body",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "delete_notice",
    "cr": true,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "save_routine_slot",
    "cr": true,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_semester_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_day",
        "type": "smallint",
        "description": "Sunday=0, Monday=1, Tuesday=2, Wednesday=3, Thursday=4, Friday=5, Saturday=6",
        "optional": false
      },
      {
        "name": "target_start",
        "type": "time without time zone",
        "description": "HH:mm, 24-hour time",
        "optional": false
      },
      {
        "name": "target_end",
        "type": "time without time zone",
        "description": "HH:mm, 24-hour time",
        "optional": false
      },
      {
        "name": "target_room",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "delete_routine_slot",
    "cr": true,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "save_student_bus_schedule",
    "cr": true,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_kind",
        "type": "text",
        "description": "office_open or closed; shared university bus timetable",
        "optional": false
      },
      {
        "name": "target_campus_departure",
        "type": "time without time zone",
        "description": "HH:mm, 24-hour time",
        "optional": false
      },
      {
        "name": "target_city_departure",
        "type": "time without time zone",
        "description": "HH:mm, 24-hour time",
        "optional": false
      },
      {
        "name": "target_active",
        "type": "boolean",
        "description": "boolean",
        "optional": true
      }
    ],
    "destructive": false
  },
  {
    "name": "save_calendar_event",
    "cr": false,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_title",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_start",
        "type": "date",
        "description": "YYYY-MM-DD",
        "optional": false
      },
      {
        "name": "target_end",
        "type": "date",
        "description": "YYYY-MM-DD",
        "optional": false
      },
      {
        "name": "target_scope",
        "type": "text",
        "description": "university (classes and offices closed), classes (only classes closed), observance, or working_day",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "delete_calendar_event",
    "cr": false,
    "arguments": [
      {
        "name": "target_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "save_batch_course",
    "cr": false,
    "arguments": [
      {
        "name": "target_batch",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_offering",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_code",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_title",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_teacher_name",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_teacher_record",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": true
      },
      {
        "name": "target_credit",
        "type": "numeric",
        "description": "numeric",
        "optional": true
      },
      {
        "name": "target_catalog_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": true
      }
    ],
    "destructive": false
  },
  {
    "name": "remove_batch_course",
    "cr": false,
    "arguments": [
      {
        "name": "target_offering",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "delete_global_course",
    "cr": false,
    "arguments": [
      {
        "name": "target_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "create_department",
    "cr": false,
    "arguments": [
      {
        "name": "target_name",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_code",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "configure_department",
    "cr": false,
    "arguments": [
      {
        "name": "target_department",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_prefix",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_offset",
        "type": "smallint",
        "description": "smallint",
        "optional": false
      },
      {
        "name": "target_active",
        "type": "boolean",
        "description": "boolean",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "create_batch",
    "cr": false,
    "arguments": [
      {
        "name": "target_department",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_batch_number",
        "type": "smallint",
        "description": "smallint",
        "optional": false
      },
      {
        "name": "target_session",
        "type": "smallint",
        "description": "Numeric email ending year (25 means 24-25)",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "publish_semester",
    "cr": false,
    "arguments": [
      {
        "name": "target_semester",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "clone_semester",
    "cr": false,
    "arguments": [
      {
        "name": "from_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "to_id",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "assign_cr",
    "cr": false,
    "arguments": [
      {
        "name": "target_profile",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_batch",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "valid_until",
        "type": "timestamp with time zone",
        "description": "timestamp with time zone",
        "optional": true
      }
    ],
    "destructive": false
  },
  {
    "name": "revoke_cr",
    "cr": false,
    "arguments": [
      {
        "name": "target_profile",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "approve_student_profile",
    "cr": false,
    "arguments": [
      {
        "name": "target_profile",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "corrected_student_id",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_batch",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "corrected_session",
        "type": "smallint",
        "description": "Numeric email ending year (25 means 24-25)",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "reject_student_profile",
    "cr": false,
    "arguments": [
      {
        "name": "target_profile",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "reason",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "owner_create_teacher",
    "cr": false,
    "arguments": [
      {
        "name": "target_department",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_name",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_email",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "owner_save_teacher",
    "cr": false,
    "arguments": [
      {
        "name": "target_record",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_name",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_email",
        "type": "text",
        "description": "text",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "owner_assign_teacher_record",
    "cr": false,
    "arguments": [
      {
        "name": "target_record",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_offering",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "set_teacher_allowlist",
    "cr": false,
    "arguments": [
      {
        "name": "target_email",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_department",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_active",
        "type": "boolean",
        "description": "boolean",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "assign_teacher_to_course",
    "cr": false,
    "arguments": [
      {
        "name": "target_teacher",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_semester_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "edit_resource_metadata",
    "cr": false,
    "arguments": [
      {
        "name": "target_resource",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      },
      {
        "name": "target_title",
        "type": "text",
        "description": "text",
        "optional": false
      },
      {
        "name": "target_category",
        "type": "text",
        "description": "notes, slides, questions, syllabus, or other",
        "optional": false
      },
      {
        "name": "target_course",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": false
  },
  {
    "name": "ai_delete_resource",
    "cr": false,
    "arguments": [
      {
        "name": "target_resource",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": true
  },
  {
    "name": "retry_failed_notifications",
    "cr": false,
    "arguments": [
      {
        "name": "target_event",
        "type": "uuid",
        "description": "Existing UUID; null only when creating or clearing",
        "optional": false
      }
    ],
    "destructive": false
  }
] as const;
