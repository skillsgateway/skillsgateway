package dev.skillsgateway.server;

/**
 * License texts the tests plant into upstream fixtures. Real opening passages — the fingerprints
 * the detector matches are phrases from the canonical texts, so the fixtures must carry them.
 */
final class LicenseFixtures {

    private LicenseFixtures() {}

    static final String MANIFEST_WITH_LICENSES =
            """
            {
              "name": "test-marketplace",
              "owner": {"name": "Test"},
              "license": "mit",
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "test", "license": "ISC"}
              ]
            }
            """;

    static final String MIT =
            """
            MIT License

            Copyright (c) 2026 Test

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
            """;

    static final String APACHE_2_0 =
            """
                                             Apache License
                                       Version 2.0, January 2004
                                    http://www.apache.org/licenses/

               TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

               1. Definitions.

                  "License" shall mean the terms and conditions for use, reproduction,
                  and distribution as defined by Sections 1 through 9 of this document.
            """;

    static final String GPL_3_0 =
            """
                                GNU GENERAL PUBLIC LICENSE
                                   Version 3, 29 June 2007

             Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
             Everyone is permitted to copy and distribute verbatim copies
             of this license document, but changing it is not allowed.
            """;

    static final String AGPL_3_0 =
            """
                                GNU AFFERO GENERAL PUBLIC LICENSE
                                   Version 3, 19 November 2007

             Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
             Everyone is permitted to copy and distribute verbatim copies
             of this license document, but changing it is not allowed.
            """;

    /** A license file identified only by its SPDX tag. */
    static final String SPDX_TAG_ONLY = """
            SPDX-License-Identifier: BSD-3-Clause

            See the organisation's shared license repository for the full text.
            """;

    /** License-shaped prose that matches no known fingerprint: the unknown-license state. */
    static final String UNRECOGNIZABLE =
            """
            HOUSE LICENSE

            You may use this software on Tuesdays, provided the moon is waxing and the
            build is green. All other use requires a handwritten letter to the authors.
            """;

    /** As {@link #UNRECOGNIZABLE}, for tests that need a second distinct unknown text. */
    static final String GIBBERISH = """
            INTERNAL TERMS

            Usage of this component is governed by internal policy document TP-7,
            available on the intranet.
            """;
}
